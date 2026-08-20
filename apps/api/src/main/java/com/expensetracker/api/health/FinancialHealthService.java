package com.expensetracker.api.health;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.expensetracker.api.budgets.BudgetService;
import com.expensetracker.api.budgets.BudgetView;
import com.expensetracker.api.cards.CardService;
import com.expensetracker.api.cards.CardView;
import com.expensetracker.api.health.HealthFacts.BudgetFact;
import com.expensetracker.api.profile.UserSettings;
import com.expensetracker.api.recurring.RecurringView;
import com.expensetracker.api.recurring.RecurringService;

/**
 * Measures the ledger, then hands the numbers to {@link HealthScorer}.
 *
 * <p>Nothing is stored. The score is recomputed on every read for the same
 * reason budgets and recurring payments are: a cached score would keep telling
 * the user they were doing badly after they had fixed the thing it was
 * complaining about.
 *
 * <p>Budgets, cards and recurring payments come from their own services rather
 * than from fresh SQL here. The health page must never claim a budget is blown
 * that the budgets page shows as on track, and the only way to guarantee that
 * is to ask the same code.
 */
@Service
public class FinancialHealthService {

    /**
     * How far back to look.
     *
     * <p>Long enough that one unusual month cannot dominate, short enough that
     * a change made two months ago is visible. A year would be steadier and
     * almost useless as feedback.
     */
    private static final int WINDOW_MONTHS = 3;

    /**
     * Everything that counts as money in or out.
     *
     * <p>Transfers are excluded because moving money between your own accounts
     * is neither earning nor spending, and counting it would show a saver with
     * two accounts as having twice the income. Excluded, deleted and
     * merged-away rows are dropped for the same reasons they are dropped from
     * budgets — a duplicate counted twice would understate the savings rate.
     */
    private static final String FLOW = """
            select count(*) as txn_count,
                   coalesce(sum(amount) filter (where kind = 'income'), 0) as income,
                   coalesce(sum(amount) filter (where kind = 'expense'), 0) as expense
            from transactions
            where user_id = ?
              and deleted_at is null
              and merged_into_id is null
              and is_excluded = false
              and kind <> 'transfer'
              and occurred_at >= ?
              and occurred_at < ?
            """;

    private static final String LIQUID = """
            select coalesce(sum(account_balance(a.id)), 0)
            from accounts a
            where a.user_id = ? and a.is_archived = false and a.type <> 'credit_card'
            """;

    private final JdbcTemplate jdbc;
    private final UserSettings settings;
    private final BudgetService budgets;
    private final CardService cards;
    private final RecurringService recurring;

    public FinancialHealthService(JdbcTemplate jdbc, UserSettings settings,
            BudgetService budgets, CardService cards, RecurringService recurring) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.budgets = budgets;
        this.cards = cards;
        this.recurring = recurring;
    }

    public HealthReport report(UUID userId) {
        return HealthScorer.score(gather(userId));
    }

    HealthFacts gather(UUID userId) {
        ZoneId zone = settings.zoneOf(userId);
        LocalDate today = settings.today(userId);
        String currency = settings.baseCurrency(userId);

        Window window = windowFor(userId, today, zone);
        if (window.months() == 0) {
            return empty(window, currency);
        }

        Flow flow = jdbc.queryForObject(FLOW,
                (rs, n) -> new Flow(rs.getInt("txn_count"),
                        rs.getBigDecimal("income"), rs.getBigDecimal("expense")),
                userId,
                Timestamp.from(window.start().atStartOfDay(zone).toInstant()),
                Timestamp.from(window.endExclusive().atStartOfDay(zone).toInstant()));

        BigDecimal months = BigDecimal.valueOf(window.months());
        BigDecimal monthlyIncome = flow.income().signum() == 0
                ? null
                : flow.income().divide(months, 2, RoundingMode.HALF_UP);
        BigDecimal monthlyExpense = flow.expense().divide(months, 2, RoundingMode.HALF_UP);

        Cards cardTotals = cardTotals(userId);
        Commitments commitments = commitments(userId);

        List<BudgetFact> budgetFacts = budgets.list(userId, false).stream()
                .map(b -> new BudgetFact(b.name(), b.amount(), b.status()))
                .toList();

        return new HealthFacts(
                window.months(), window.start(), window.endInclusive(), flow.count(),
                monthlyIncome, monthlyExpense,
                liquidBalance(userId), cardTotals.debt(),
                cardTotals.limit(), cardTotals.outstanding(),
                budgetFacts, commitments.monthly(), commitments.count(), currency);
    }

    /**
     * The last few complete calendar months.
     *
     * <p>The current month is always left out. On the 2nd it would contribute
     * two days of income against two days of spending, and a monthly average
     * built from that is nonsense. A partial first month is left out for the
     * same reason: if the user's history starts on the 20th, January holds a
     * third of a salary and would drag every average down for the next two
     * months.
     */
    private Window windowFor(UUID userId, LocalDate today, ZoneId zone) {
        YearMonth lastComplete = YearMonth.from(today).minusMonths(1);

        Timestamp earliest = jdbc.queryForObject("""
                select min(occurred_at) from transactions
                where user_id = ? and deleted_at is null and merged_into_id is null
                """, Timestamp.class, userId);

        if (earliest == null) {
            return new Window(0, lastComplete.atDay(1), lastComplete.atDay(1));
        }

        LocalDate first = LocalDate.ofInstant(earliest.toInstant(), zone);
        YearMonth firstComplete = first.getDayOfMonth() == 1
                ? YearMonth.from(first)
                : YearMonth.from(first).plusMonths(1);

        if (firstComplete.isAfter(lastComplete)) {
            return new Window(0, lastComplete.atDay(1), lastComplete.atDay(1));
        }

        long available = ChronoUnit.MONTHS.between(firstComplete, lastComplete) + 1;
        int months = (int) Math.min(available, WINDOW_MONTHS);
        YearMonth start = lastComplete.minusMonths(months - 1L);

        return new Window(months, start.atDay(1), lastComplete.atEndOfMonth());
    }

    private BigDecimal liquidBalance(UUID userId) {
        BigDecimal total = jdbc.queryForObject(LIQUID, BigDecimal.class, userId);
        return total == null ? BigDecimal.ZERO : total;
    }

    /**
     * Card totals, split into the two questions they answer.
     *
     * <p>{@code debt} covers every card, because all of it has to be repaid out
     * of the same cash. {@code limit} and {@code outstanding} cover only cards
     * that declare a limit, so utilisation is not diluted by a card whose limit
     * the user never entered.
     */
    private Cards cardTotals(UUID userId) {
        BigDecimal debt = BigDecimal.ZERO;
        BigDecimal limit = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        boolean anyLimit = false;

        for (CardView card : cards.list(userId, false)) {
            debt = debt.add(card.outstanding());
            if (card.creditLimit() != null && card.creditLimit().signum() > 0) {
                anyLimit = true;
                limit = limit.add(card.creditLimit());
                outstanding = outstanding.add(card.outstanding());
            }
        }

        return new Cards(debt, anyLimit ? limit : null, outstanding);
    }

    /**
     * Confirmed outgoing subscriptions, expressed per month.
     *
     * <p>Only what the user has confirmed. A suggestion the detector is still
     * unsure about would make the score drift on its own, and a number that
     * moves without the user doing anything is one they stop trusting.
     */
    private Commitments commitments(UUID userId) {
        List<RecurringView> confirmed = recurring.list(userId, false).stream()
                .filter(r -> "confirmed".equals(r.state()))
                .filter(r -> "debit".equals(r.direction()))
                .filter(RecurringView::isActive)
                .filter(r -> !"ended".equals(r.status()))
                .toList();

        BigDecimal monthly = confirmed.stream()
                .map(RecurringView::monthlyCost)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Commitments(monthly, confirmed.size());
    }

    private HealthFacts empty(Window window, String currency) {
        return new HealthFacts(0, window.start(), window.endInclusive(), 0,
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, BigDecimal.ZERO, List.of(), BigDecimal.ZERO, 0, currency);
    }

    private record Window(int months, LocalDate start, LocalDate endInclusive) {
        LocalDate endExclusive() {
            return endInclusive.plusDays(1);
        }
    }

    private record Flow(int count, BigDecimal income, BigDecimal expense) {
    }

    private record Cards(BigDecimal debt, BigDecimal limit, BigDecimal outstanding) {
    }

    private record Commitments(BigDecimal monthly, int count) {
    }
}
