package com.expensetracker.api.insights;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * The month being looked at, and the stretch of the previous month it is fair
 * to compare against.
 *
 * <p>The comparison is the whole point of a dashboard and it is easy to get
 * dishonestly wrong. Comparing twelve days of this month against all thirty of
 * last month would tell every user, every month, that their spending has
 * collapsed. So while the month in view is still running, only the same number
 * of days of the previous month is counted.
 *
 * <p>Everything here is a {@link LocalDate} in the user's own calendar. Turning
 * those into instants is the caller's job, because only it knows the timezone —
 * and doing it here would make this class untestable without a database.
 *
 * @param daysElapsed days of this month that have actually happened; the whole
 *                    month once it is over, and zero for a month yet to start
 * @param partial     whether the month is still running, which is what makes
 *                    the comparison window shorter than a whole month
 */
public record InsightsWindow(
        YearMonth month,
        LocalDate start,
        LocalDate endExclusive,
        LocalDate previousStart,
        LocalDate previousEndExclusive,
        int daysElapsed,
        int daysInMonth,
        boolean partial) {

    /**
     * Days that must have passed before a month's spending is projected to its
     * end. Two days in, one large payment triples the estimate; a number that
     * swings like that is worse than none, because people believe it.
     */
    private static final int PROJECTION_FLOOR = 5;

    public static InsightsWindow of(YearMonth month, LocalDate today) {
        YearMonth current = YearMonth.from(today);
        int daysInMonth = month.lengthOfMonth();

        int daysElapsed;
        boolean partial;
        if (month.isAfter(current)) {
            // A month that has not started. Nothing has happened in it, and
            // "0 spent, down 100%" would be noise rather than news.
            daysElapsed = 0;
            partial = true;
        } else if (month.equals(current)) {
            daysElapsed = today.getDayOfMonth();
            partial = daysElapsed < daysInMonth;
        } else {
            daysElapsed = daysInMonth;
            partial = false;
        }

        YearMonth previous = month.minusMonths(1);
        LocalDate previousStart = previous.atDay(1);

        // February cannot supply a 31st day. Clamping keeps the comparison as
        // long as it honestly can be, rather than running into March.
        int previousDays = Math.min(daysElapsed, previous.lengthOfMonth());

        return new InsightsWindow(
                month,
                month.atDay(1),
                month.plusMonths(1).atDay(1),
                previousStart,
                previousStart.plusDays(previousDays),
                daysElapsed,
                daysInMonth,
                partial);
    }

    /** True when the month has run long enough for a projection to mean anything. */
    public boolean canProject() {
        return partial && daysElapsed >= PROJECTION_FLOOR;
    }

    /**
     * How many days of the previous month are being counted.
     *
     * <p>Exposed so the interface can say "compared with the same 12 days last
     * month" rather than the vaguer, and here untrue, "compared with last month".
     */
    public int previousDaysCounted() {
        return (int) ChronoUnit.DAYS.between(previousStart, previousEndExclusive);
    }
}
