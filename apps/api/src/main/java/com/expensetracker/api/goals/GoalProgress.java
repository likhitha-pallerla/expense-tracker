package com.expensetracker.api.goals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How a goal is going.
 *
 * <p>Pure and fully unit-tested, because almost every field here is a way to
 * mislead someone about their own money if it is computed slightly wrong.
 *
 * <p>The governing rule is that <b>null means "we cannot say"</b>, and is used
 * freely. A goal with no target date has no pace and cannot be behind. A goal
 * created three days ago has no trend worth reporting. Filling those in with a
 * zero or a {@code false} would turn "we do not know" into a confident and
 * wrong answer, which is the one outcome worth avoiding.
 *
 * @param percent          progress toward the target, capped at 100 — going
 *                         over is not 143% of a goal, it is a met goal and a
 *                         surplus
 * @param daysLeft         negative once the date has passed, so the interface
 *                         can say "9 days late" rather than losing the sign
 * @param requiredPerMonth what must go in from now on to arrive on time
 * @param actualPerMonth   what has been going in, measured from the first
 *                         contribution rather than from when the goal was
 *                         created — the gap between deciding to save and
 *                         starting to save is not a period of bad saving
 * @param projectedDate    when the target is reached at the current pace, or
 *                         null when nothing is going in and the honest answer
 *                         is "never"
 * @param onTrack          null when unknowable, which is common and fine
 * @param planShortfall    how far a stated monthly plan falls short of what the
 *                         date actually requires; null when there is no plan or
 *                         no date to check it against
 */
public record GoalProgress(
        BigDecimal target,
        BigDecimal saved,
        BigDecimal remaining,
        BigDecimal percent,
        boolean achieved,
        Integer daysLeft,
        BigDecimal monthsLeft,
        boolean overdue,
        BigDecimal requiredPerMonth,
        BigDecimal actualPerMonth,
        LocalDate projectedDate,
        Boolean onTrack,
        BigDecimal planShortfall) {

    /**
     * The average length of a month over a four-year cycle.
     *
     * <p>Used rather than 30 so a "per month" figure does not drift by five
     * days a year, and rather than whole calendar months so the number moves
     * smoothly instead of lurching on the 1st.
     */
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.436875");

    /**
     * How much history it takes before a pace means anything.
     *
     * <p>Below this, one deposit either flatters someone enormously or makes a
     * brand-new goal look abandoned. Neither is worth telling them.
     */
    static final int MIN_DAYS_OF_HISTORY = 14;

    private static final int SCALE = 2;

    /**
     * @param firstContribution when saving actually started; null if it has not
     * @param monthlyTarget     what the user says they intend to put in monthly
     */
    public static GoalProgress of(
            BigDecimal target,
            BigDecimal saved,
            LocalDate targetDate,
            LocalDate firstContribution,
            BigDecimal monthlyTarget,
            LocalDate today) {

        BigDecimal safeTarget = target == null ? BigDecimal.ZERO : target;
        BigDecimal safeSaved = saved == null ? BigDecimal.ZERO : saved;

        boolean achieved = safeTarget.signum() > 0
                && safeSaved.compareTo(safeTarget) >= 0;

        BigDecimal remaining = safeTarget.subtract(safeSaved).max(BigDecimal.ZERO)
                .setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal percent = percentOf(safeSaved, safeTarget);

        Integer daysLeft = targetDate == null
                ? null
                : (int) ChronoUnit.DAYS.between(today, targetDate);
        BigDecimal monthsLeft = daysLeft == null ? null : months(daysLeft);
        boolean overdue = daysLeft != null && daysLeft < 0 && !achieved;

        BigDecimal required = required(remaining, daysLeft, achieved);

        long historyDays = firstContribution == null
                ? 0
                : ChronoUnit.DAYS.between(firstContribution, today);
        boolean hasPace = firstContribution != null
                && safeSaved.signum() > 0
                && historyDays >= MIN_DAYS_OF_HISTORY;

        BigDecimal actual = hasPace ? perMonth(safeSaved, historyDays) : null;
        LocalDate projected = projected(
                remaining, safeSaved, historyDays, hasPace, achieved, today);

        return new GoalProgress(
                safeTarget, safeSaved, remaining, percent, achieved,
                daysLeft, monthsLeft, overdue, required, actual, projected,
                onTrack(achieved, targetDate, projected, actual),
                planShortfall(monthlyTarget, required));
    }

    private static BigDecimal percentOf(BigDecimal saved, BigDecimal target) {
        if (target.signum() <= 0) return BigDecimal.ZERO.setScale(SCALE);
        return saved.multiply(BigDecimal.valueOf(100))
                .divide(target, SCALE, RoundingMode.HALF_UP)
                .min(BigDecimal.valueOf(100))
                .max(BigDecimal.ZERO);
    }

    /** A span of days as a number of months, for display. */
    private static BigDecimal months(long days) {
        return BigDecimal.valueOf(days)
                .divide(DAYS_PER_MONTH, 4, RoundingMode.HALF_UP);
    }

    /**
     * An amount spread over a span of days, expressed per month.
     *
     * <p>Deliberately one division rather than two. Converting the days to
     * months first and then dividing by that rounded figure loses accuracy
     * twice over, and the error grows with the amount — on a large goal it is
     * visible in rupees, which is exactly the kind of small wrongness that
     * makes someone stop believing the rest of the page.
     *
     * <p>Measured from the first contribution, never from when the goal was
     * created. Someone who set a goal in January and started saving in March
     * has been saving for two months, and dividing by five would tell them they
     * are doing worse than they are.
     */
    private static BigDecimal perMonth(BigDecimal amount, long days) {
        return amount.multiply(DAYS_PER_MONTH)
                .divide(BigDecimal.valueOf(days), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * What must go in each month from here.
     *
     * <p>Once the date has passed there is no "per month" left to spread it
     * over, so the whole remainder is what is required — now, not monthly. The
     * interface says so in words rather than dividing by a fortnight and
     * quoting an absurd figure.
     */
    private static BigDecimal required(
            BigDecimal remaining, Integer daysLeft, boolean achieved) {

        if (achieved || daysLeft == null) return null;
        if (daysLeft <= 0) return remaining;

        return perMonth(remaining, daysLeft);
    }

    /**
     * When the target is reached if nothing changes.
     *
     * <p>Worked out from the raw history — amount saved over days elapsed —
     * rather than from the rounded monthly figure above. Going through a
     * two-decimal number and then rounding days up would push a goal that is
     * exactly on pace one day past its own deadline and report it as behind,
     * which is a rounding error masquerading as bad news.
     */
    private static LocalDate projected(
            BigDecimal remaining, BigDecimal saved, long historyDays,
            boolean hasPace, boolean achieved, LocalDate today) {

        if (achieved) return today;
        if (!hasPace) return null;

        long daysNeeded = remaining.multiply(BigDecimal.valueOf(historyDays))
                .divide(saved, 0, RoundingMode.CEILING)
                .longValueExact();

        return today.plusDays(daysNeeded);
    }

    /**
     * Whether they will make it, where that can be judged at all.
     *
     * <p>Null in three separate situations that all mean "no opinion": no date
     * to arrive by, no saving history to extrapolate from, and nothing going in
     * at all. Only the last of those is bad news, and it is bad news the
     * interface delivers in words rather than as a bare {@code false}.
     */
    private static Boolean onTrack(
            boolean achieved, LocalDate targetDate,
            LocalDate projected, BigDecimal actual) {

        if (achieved) return true;
        if (targetDate == null) return null;
        if (actual == null) return null;
        if (projected == null) return false;

        return !projected.isAfter(targetDate);
    }

    private static BigDecimal planShortfall(BigDecimal monthlyTarget, BigDecimal required) {
        if (monthlyTarget == null || required == null) return null;
        return required.subtract(monthlyTarget).max(BigDecimal.ZERO);
    }

    /** True when a stated monthly plan is not enough to hit the date. */
    @JsonProperty("planFallsShort")
    public boolean planFallsShort() {
        return planShortfall != null && planShortfall.signum() > 0;
    }

    /** True when nothing has gone in yet, which reads differently from "behind". */
    @JsonProperty("notStarted")
    public boolean notStarted() {
        return saved.signum() <= 0;
    }
}
