package com.expensetracker.api.insights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.expensetracker.api.profile.UserSettings;

/**
 * Where the money went, month by month.
 *
 * <p>Every figure is computed from the ledger on read. Nothing is cached and no
 * running totals are kept: a dashboard that disagrees with the transaction list
 * is worse than no dashboard, and the only way to guarantee they agree is to
 * ask the same question of the same rows.
 *
 * <p>All aggregation happens in Postgres. Pulling a month of transactions into
 * Java to sum them would work today and fall over the first time someone has a
 * few years of history on a free instance with 512 MB.
 */
@Service
public class InsightsService {

    /** How many months the trend line covers, including the one in view. */
    private static final int TREND_MONTHS = 6;

    /** Categories shown before the rest are folded away. */
    private static final int CATEGORY_LIMIT = 12;

    private static final int MERCHANT_LIMIT = 8;

    /** Category changes smaller than this are noise, not news. */
    private static final BigDecimal MOVER_FLOOR = new BigDecimal("100");

    private static final int MOVER_LIMIT = 3;

    /**
     * What counts as money moving.
     *
     * <p>Transfers are excluded because moving your own money between accounts
     * is neither earning nor spending; counting it would show anyone with two
     * accounts as having twice their income. Excluded, soft-deleted and
     * merged-away rows are dropped for the same reason budgets drop them — a
     * duplicate counted twice overstates spending and would make the whole
     * dashboard untrustworthy.
     */
    private static final String LEDGER = """
             and t.deleted_at is null
             and t.merged_into_id is null
             and t.is_excluded = false
             and t.kind <> 'transfer'
            """;

    private final JdbcTemplate jdbc;
    private final UserSettings settings;

    public InsightsService(JdbcTemplate jdbc, UserSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public Insights forMonth(UUID userId, YearMonth month) {
        ZoneId zone = settings.zoneOf(userId);
        LocalDate today = LocalDate.now(zone);
        InsightsWindow window = InsightsWindow.of(month, today);

        Instant start = at(window.start(), zone);
        Instant end = at(window.endExclusive(), zone);
        Instant previousStart = at(window.previousStart(), zone);
        Instant previousEnd = at(window.previousEndExclusive(), zone);

        Totals totals = totals(userId, start, end);
        Totals previous = totals(userId, previousStart, previousEnd);

        List<CategorySlice> categories = categories(userId, totals.expense(),
                start, end, previousStart, previousEnd);

        String base = settings.baseCurrency(userId);
        YearMonth earliest = earliestMonth(userId, zone);

        return new Insights(
                month.toString(),
                label(month),
                base,
                window.partial(),
                window.daysElapsed(),
                window.daysInMonth(),
                window.previousDaysCounted(),
                totals,
                previous,
                Totals.percentChange(totals.income(), previous.income()),
                Totals.percentChange(totals.expense(), previous.expense()),
                project(totals.expense(), window),
                uncategorised(categories),
                trim(categories),
                movers(categories),
                merchants(userId, start, end),
                trend(userId, month, today, zone),
                mixedCurrencies(userId, start, end, base),
                earliest != null,
                earliest == null ? null : earliest.toString(),
                YearMonth.from(today).toString());
    }

    /**
     * The month this user's records begin.
     *
     * <p>Asked of the whole ledger rather than the window on purpose. Someone
     * paging back to a month where nothing happened should be shown an empty
     * month, not the first-run instructions — those two states look nothing
     * alike and confusing them is insulting. It also tells the interface when to
     * stop offering a step further back.
     */
    private YearMonth earliestMonth(UUID userId, ZoneId zone) {
        Timestamp earliest = jdbc.queryForObject("""
                select min(t.occurred_at) from transactions t
                 where t.user_id = ?
                """ + LEDGER, Timestamp.class, userId);

        return earliest == null
                ? null
                : YearMonth.from(LocalDate.ofInstant(earliest.toInstant(), zone));
    }

    // ---- totals ------------------------------------------------------------

    private Totals totals(UUID userId, Instant start, Instant end) {
        if (!start.isBefore(end)) {
            // Happens for a month that has not started. No query is needed and
            // an empty range would only produce zeroes anyway.
            return Totals.EMPTY;
        }
        return jdbc.queryForObject("""
                select coalesce(sum(t.amount) filter (where t.kind = 'income'), 0) as income,
                       coalesce(sum(t.amount) filter (where t.kind = 'expense'), 0) as expense,
                       count(*) as txn_count
                  from transactions t
                 where t.user_id = ?
                   and t.occurred_at >= ? and t.occurred_at < ?
                """ + LEDGER,
                (rs, row) -> Totals.of(rs.getBigDecimal("income"), rs.getBigDecimal("expense"),
                        rs.getInt("txn_count")),
                userId, Timestamp.from(start), Timestamp.from(end));
    }

    // ---- categories --------------------------------------------------------

    /**
     * This month and the comparable stretch of last month, in one pass.
     *
     * <p>Two queries would be simpler to read and would have to be stitched
     * together in Java anyway; one pass over a single index range is both faster
     * and immune to a transaction landing between the two reads.
     */
    private List<CategorySlice> categories(UUID userId, BigDecimal monthExpense,
            Instant start, Instant end, Instant previousStart, Instant previousEnd) {

        Instant outerStart = previousStart.isBefore(start) ? previousStart : start;
        if (!outerStart.isBefore(end)) {
            return List.of();
        }

        List<CategorySlice> rows = jdbc.query("""
                select t.category_id,
                       coalesce(c.name, 'Uncategorised') as name,
                       coalesce(sum(t.amount) filter (
                           where t.occurred_at >= ? and t.occurred_at < ?), 0) as amount,
                       coalesce(sum(t.amount) filter (
                           where t.occurred_at >= ? and t.occurred_at < ?), 0) as previous_amount,
                       count(*) filter (
                           where t.occurred_at >= ? and t.occurred_at < ?) as txn_count
                  from transactions t
                  left join categories c on c.id = t.category_id
                 where t.user_id = ?
                   and t.kind = 'expense'
                   and t.occurred_at >= ? and t.occurred_at < ?
                """ + LEDGER + """
                 group by t.category_id, coalesce(c.name, 'Uncategorised')
                """,
                (rs, row) -> slice(rs.getObject("category_id", UUID.class),
                        rs.getString("name"),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("previous_amount"),
                        rs.getInt("txn_count"),
                        monthExpense),
                Timestamp.from(start), Timestamp.from(end),
                Timestamp.from(previousStart), Timestamp.from(previousEnd),
                Timestamp.from(start), Timestamp.from(end),
                userId,
                Timestamp.from(outerStart), Timestamp.from(end));

        // A category spent on last month and not this one is still worth
        // showing — that is the whole story of a habit stopping — but a row
        // that is zero on both sides is a leftover from a wider outer range.
        return rows.stream()
                .filter(slice -> slice.amount().signum() != 0 || slice.previousAmount().signum() != 0)
                .sorted(Comparator.comparing(CategorySlice::amount).reversed()
                        .thenComparing(CategorySlice::name))
                .toList();
    }

    private CategorySlice slice(UUID categoryId, String name, BigDecimal amount,
            BigDecimal previousAmount, int count, BigDecimal monthExpense) {
        BigDecimal now = amount == null ? BigDecimal.ZERO : amount;
        BigDecimal before = previousAmount == null ? BigDecimal.ZERO : previousAmount;

        BigDecimal share = monthExpense == null || monthExpense.signum() == 0
                ? BigDecimal.ZERO
                : now.multiply(BigDecimal.valueOf(100))
                        .divide(monthExpense, 1, RoundingMode.HALF_UP);

        return new CategorySlice(categoryId, name, now, before,
                now.subtract(before), Totals.percentChange(now, before), share, count);
    }

    /**
     * Folds the long tail into one row.
     *
     * <p>A list of forty categories is a table, not an insight. What is cut is
     * still counted — the folded row carries the rest of the total, so the parts
     * add up to the month.
     */
    private List<CategorySlice> trim(List<CategorySlice> categories) {
        if (categories.size() <= CATEGORY_LIMIT) {
            return categories;
        }

        List<CategorySlice> kept = new ArrayList<>(categories.subList(0, CATEGORY_LIMIT - 1));
        List<CategorySlice> rest = categories.subList(CATEGORY_LIMIT - 1, categories.size());

        BigDecimal amount = rest.stream().map(CategorySlice::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal previous = rest.stream().map(CategorySlice::previousAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal share = rest.stream().map(CategorySlice::share)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int count = rest.stream().mapToInt(CategorySlice::count).sum();

        kept.add(new CategorySlice(null, rest.size() + " smaller categories", amount, previous,
                amount.subtract(previous), Totals.percentChange(amount, previous), share, count));
        return List.copyOf(kept);
    }

    /**
     * The categories that changed most, in either direction.
     *
     * <p>Falls are included, not just rises. Spending less on something is the
     * only feedback that tells a user a decision worked, and a dashboard that
     * only ever reports bad news stops being opened.
     */
    private List<CategorySlice> movers(List<CategorySlice> categories) {
        return categories.stream()
                .filter(slice -> slice.delta().abs().compareTo(MOVER_FLOOR) >= 0)
                .sorted(Comparator.comparing((CategorySlice slice) -> slice.delta().abs())
                        .reversed())
                .limit(MOVER_LIMIT)
                .toList();
    }

    private BigDecimal uncategorised(List<CategorySlice> categories) {
        return categories.stream()
                .filter(CategorySlice::isUncategorised)
                .map(CategorySlice::amount)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    // ---- merchants ---------------------------------------------------------

    private List<MerchantSlice> merchants(UUID userId, Instant start, Instant end) {
        if (!start.isBefore(end)) {
            return List.of();
        }
        return jdbc.query("""
                select m.id, m.name, sum(t.amount) as amount, count(*) as txn_count
                  from transactions t
                  join merchants m on m.id = t.merchant_id
                 where t.user_id = ?
                   and t.kind = 'expense'
                   and t.occurred_at >= ? and t.occurred_at < ?
                """ + LEDGER + """
                 group by m.id, m.name
                 order by amount desc, m.name
                 limit ?
                """,
                (rs, row) -> new MerchantSlice(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getBigDecimal("amount"),
                        rs.getInt("txn_count")),
                userId, Timestamp.from(start), Timestamp.from(end), MERCHANT_LIMIT);
    }

    // ---- trend -------------------------------------------------------------

    /**
     * The last few months, including empty ones.
     *
     * <p>Grouping happens in the user's timezone, not UTC. A payment at half
     * past midnight on the 1st of a month in Kolkata is 19:00 on the last day of
     * the previous month in UTC, and bucketing it there would move real money
     * between months on a chart the user is trying to reason about.
     *
     * <p>Months with nothing in them are filled with zeroes rather than left
     * out, so a gap reads as a gap instead of the line jumping across it.
     */
    private List<TrendPoint> trend(UUID userId, YearMonth month, LocalDate today, ZoneId zone) {
        YearMonth first = month.minusMonths(TREND_MONTHS - 1L);
        Instant start = at(first.atDay(1), zone);
        Instant end = at(month.plusMonths(1).atDay(1), zone);

        Map<String, BigDecimal[]> byMonth = new LinkedHashMap<>();
        if (start.isBefore(end)) {
            jdbc.query("""
                    select to_char(date_trunc('month', t.occurred_at at time zone ?), 'YYYY-MM')
                               as bucket,
                           coalesce(sum(t.amount) filter (where t.kind = 'income'), 0) as income,
                           coalesce(sum(t.amount) filter (where t.kind = 'expense'), 0) as expense
                      from transactions t
                     where t.user_id = ?
                       and t.occurred_at >= ? and t.occurred_at < ?
                    """ + LEDGER + """
                     group by 1
                    """,
                    rs -> {
                        byMonth.put(rs.getString("bucket"), new BigDecimal[] {
                                rs.getBigDecimal("income"), rs.getBigDecimal("expense") });
                    },
                    zone.getId(), userId, Timestamp.from(start), Timestamp.from(end));
        }

        YearMonth current = YearMonth.from(today);
        List<TrendPoint> points = new ArrayList<>(TREND_MONTHS);
        for (int i = 0; i < TREND_MONTHS; i++) {
            YearMonth at = first.plusMonths(i);
            BigDecimal[] found = byMonth.get(at.toString());
            points.add(TrendPoint.of(at,
                    found == null ? BigDecimal.ZERO : found[0],
                    found == null ? BigDecimal.ZERO : found[1],
                    !at.isBefore(current)));
        }
        return List.copyOf(points);
    }

    // ---- odds and ends -----------------------------------------------------

    /**
     * Where the month looks likely to end up.
     *
     * <p>A straight-line pace, which is wrong in a knowable way: rent lands on
     * the 1st and salary on the 30th, so early in a month this reads high and
     * late it reads low. It is offered only after enough days have passed for
     * that error to be small, and never for a month that is already over — a
     * projection of the past is just the past with extra confusion.
     */
    private BigDecimal project(BigDecimal expense, InsightsWindow window) {
        if (!window.canProject() || expense.signum() == 0) {
            return null;
        }
        return expense
                .multiply(BigDecimal.valueOf(window.daysInMonth()))
                .divide(BigDecimal.valueOf(window.daysElapsed()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Whether the month mixes currencies.
     *
     * <p>The totals add everything up regardless, because the alternative — a
     * total that silently leaves money out — is worse. This flag is how the
     * interface gets to be honest about the fact that the sum is approximate.
     */
    private boolean mixedCurrencies(UUID userId, Instant start, Instant end, String base) {
        if (!start.isBefore(end)) {
            return false;
        }
        Integer others = jdbc.queryForObject("""
                select count(*) from (
                    select 1 from transactions t
                     where t.user_id = ?
                       and t.occurred_at >= ? and t.occurred_at < ?
                       and t.currency is distinct from ?
                """ + LEDGER + """
                     limit 1
                ) as found
                """, Integer.class, userId, Timestamp.from(start), Timestamp.from(end), base);
        return others != null && others > 0;
    }

    private static Instant at(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    private static String label(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                + " " + month.getYear();
    }
}
