package com.expensetracker.api.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.expensetracker.api.profile.UserSettings;
import com.expensetracker.api.recurring.RecurringService;
import com.expensetracker.api.recurring.RecurringView;

/**
 * Looks forward instead of back.
 *
 * <p>Built entirely on recurring series the user already has, so it predicts
 * only what there is evidence for. Nothing here guesses at discretionary
 * spending: a forecast that quietly adds "and you'll probably spend ₹8,000 on
 * food" cannot be checked by the person reading it, and when it is wrong they
 * have no way to tell which part was wrong.
 *
 * <p>The series come from {@link RecurringService} rather than a query of its
 * own. Two pages disagreeing about which subscriptions you have is worse than
 * either of them being slightly wrong, and reading the table directly would
 * also miss every suspected series — those are detected from history at read
 * time and never stored.
 */
@Service
public class ForecastService {

    /**
     * How far back to look when working out ordinary day-to-day spending.
     *
     * <p>Long enough to cover the shape of a quarter without letting a house
     * move eighteen months ago distort what this month looks like.
     */
    private static final int LOOKBACK_DAYS = 90;

    private final JdbcTemplate jdbc;
    private final UserSettings settings;
    private final RecurringService recurring;

    public ForecastService(
            JdbcTemplate jdbc, UserSettings settings, RecurringService recurring) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.recurring = recurring;
    }

    public Forecast forecast(UUID userId, int days) {
        ZoneId zone = settings.zoneOf(userId);
        LocalDate today = LocalDate.now(zone);
        ForecastWindow window = ForecastWindow.of(today, days);
        String currency = settings.baseCurrency(userId);

        BigDecimal balance = balance(userId);
        int accounts = accountCount(userId);

        List<ExpectedCharge> all = expected(userId, window);
        List<ExpectedCharge> confirmed = all.stream().filter(ExpectedCharge::confirmed).toList();
        List<ExpectedCharge> suspected = all.stream().filter(c -> !c.confirmed()).toList();

        Projection.Result projected = Projection.run(window, balance, confirmed);

        BigDecimal in = confirmed.stream().filter(ExpectedCharge::isIncome)
                .map(ExpectedCharge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal out = confirmed.stream().filter(charge -> !charge.isIncome())
                .map(ExpectedCharge::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Forecast(
                today,
                window.end(),
                window.days(),
                currency,
                balance,
                in,
                out,
                projected.closing(),
                safeToSpend(projected.low()),
                projected.low(),
                projected.days(),
                confirmed,
                suspected,
                unpredicted(userId, zone),
                seriesCount(confirmed),
                mixedCurrencies(all, currency),
                accounts > 0);
    }

    /**
     * What could be spent today without the balance ever going below zero.
     *
     * <p>Measured against the low point, not the closing balance: money that
     * only arrives on the 28th is no help on the 12th. Floored at zero, because
     * "you can safely spend minus four thousand" is not advice — someone
     * already in trouble needs to be told they are in trouble, which the low
     * point does in its own words.
     */
    private BigDecimal safeToSpend(LowPoint low) {
        return low.balance().signum() > 0 ? low.balance() : BigDecimal.ZERO;
    }

    private int seriesCount(List<ExpectedCharge> charges) {
        return (int) charges.stream().map(ExpectedCharge::seriesId).distinct().count();
    }

    private BigDecimal balance(UUID userId) {
        BigDecimal total = jdbc.queryForObject("""
                select coalesce(sum(account_balance(a.id)), 0)
                  from accounts a
                 where a.user_id = ? and a.is_archived = false
                """, BigDecimal.class, userId);
        return total == null ? BigDecimal.ZERO : total;
    }

    private int accountCount(UUID userId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from accounts where user_id = ? and is_archived = false",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    /**
     * Every occurrence of every live series that lands inside the window.
     *
     * <p>One series can appear several times: a weekly charge shows up four or
     * five times in a month, and collapsing those into one row would understate
     * the month by a factor of four.
     *
     * <p>Dismissed series are already gone by the time they get here. Someone
     * who has said "this is not a subscription" should not find their money
     * still committed to it.
     */
    private List<ExpectedCharge> expected(UUID userId, ForecastWindow window) {
        List<ExpectedCharge> charges = new ArrayList<>();

        for (RecurringView series : recurring.list(userId, false)) {
            addOccurrences(charges, series, window);
        }

        charges.sort(Comparator.comparing(ExpectedCharge::expectedOn)
                .thenComparing(ExpectedCharge::name));
        return charges;
    }

    private void addOccurrences(
            List<ExpectedCharge> into, RecurringView series, ForecastWindow window) {

        int cadence = series.cadenceDays();
        if (cadence <= 0) return;

        LocalDate next = series.nextExpected();
        if (next == null) return;

        BigDecimal amount = series.typicalAmount() != null
                ? series.typicalAmount()
                : series.latestAmount();
        if (amount == null || amount.signum() <= 0) return;

        // A charge expected before today that has not arrived is late, not
        // cancelled — the rent is still coming. Roll it forward to the soonest
        // day it could still land, and say it is overdue so the date is not
        // mistaken for a firm one.
        boolean overdue = next.isBefore(window.today());
        while (next.isBefore(window.today())) {
            next = next.plusDays(cadence);
        }

        boolean confirmed = "confirmed".equals(series.state());
        boolean first = true;

        while (!next.isAfter(window.end())) {
            into.add(new ExpectedCharge(
                    series.id(), series.name(), next, window.indexOf(next), amount,
                    series.direction(), series.currency(), series.categoryId(),
                    series.categoryName(), series.cadence(), confirmed,
                    overdue && first, series.amountVaries()));
            next = next.plusDays(cadence);
            first = false;
        }
    }

    /**
     * Ordinary spending, per day, that no series accounts for.
     *
     * <p>Reported beside the forecast rather than folded into it. The user can
     * then judge for themselves whether the safe-to-spend figure survives their
     * normal week — which is a question they can answer and the system cannot.
     */
    private BigDecimal unpredicted(UUID userId, ZoneId zone) {
        BigDecimal total = jdbc.queryForObject("""
                select coalesce(sum(-t.signed_amount), 0)
                  from transactions t
                 where t.user_id = ?
                   and t.deleted_at is null
                   and t.merged_into_id is null
                   and t.is_excluded = false
                   and t.kind <> 'transfer'
                   and t.is_recurring = false
                   and t.signed_amount < 0
                   and t.occurred_at >= ?
                """, BigDecimal.class, userId,
                Timestamp.from(LocalDate.now(zone).minusDays(LOOKBACK_DAYS)
                        .atStartOfDay(zone).toInstant()));

        return total == null
                ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(LOOKBACK_DAYS), 2, RoundingMode.HALF_UP);
    }

    /**
     * Same policy as the month view: sum everything and admit the mixture.
     *
     * <p>A total that silently drops a foreign charge is worse than one that is
     * approximate, because nothing on the page would show the money missing.
     */
    private boolean mixedCurrencies(List<ExpectedCharge> charges, String base) {
        return charges.stream().anyMatch(charge -> !base.equals(charge.currency()));
    }
}
