package com.expensetracker.api.budgets;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The stretch of days a budget is currently measuring.
 *
 * <p>Windows run from the budget's own start date, not from the 1st of the
 * month. Someone paid on the 25th budgets from the 25th, and showing them a
 * calendar month would mix two pay cycles together.
 *
 * @param start        first day counted, inclusive
 * @param endExclusive first day of the next period
 * @param index        how many whole periods have passed since the budget began
 */
public record BudgetWindow(LocalDate start, LocalDate endExclusive, long index) {

    /** Last day counted, for display — users think in closed ranges. */
    public LocalDate endInclusive() {
        return endExclusive.minusDays(1);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(start) && date.isBefore(endExclusive);
    }

    public long daysRemaining(LocalDate today) {
        if (today.isBefore(start)) {
            return ChronoUnit.DAYS.between(start, endExclusive);
        }
        return Math.max(0, ChronoUnit.DAYS.between(today, endExclusive));
    }

    public long totalDays() {
        return ChronoUnit.DAYS.between(start, endExclusive);
    }

    /**
     * The window containing {@code today}, or the first window when the budget
     * has not started yet.
     *
     * <p>An estimate gets us close, then it is corrected by stepping — month
     * lengths vary, so arithmetic alone can land one window out.
     */
    public static BudgetWindow current(BudgetPeriod period, LocalDate startsOn, LocalDate today) {
        if (!today.isAfter(startsOn)) {
            return at(period, startsOn, 0);
        }

        long guess = switch (period) {
            case WEEKLY -> ChronoUnit.WEEKS.between(startsOn, today);
            case MONTHLY -> ChronoUnit.MONTHS.between(startsOn, today);
            case YEARLY -> ChronoUnit.YEARS.between(startsOn, today);
        };

        long index = Math.max(0, guess);

        // Step back while the window would start after today…
        while (index > 0 && period.advance(startsOn, index).isAfter(today)) {
            index--;
        }
        // …and forward while the next window has already begun.
        while (!period.advance(startsOn, index + 1).isAfter(today)) {
            index++;
        }

        return at(period, startsOn, index);
    }

    /** The nth window since the budget began, counting from zero. */
    public static BudgetWindow at(BudgetPeriod period, LocalDate startsOn, long index) {
        return new BudgetWindow(
                period.advance(startsOn, index),
                period.advance(startsOn, index + 1),
                index);
    }
}
