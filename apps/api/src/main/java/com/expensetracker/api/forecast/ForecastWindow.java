package com.expensetracker.api.forecast;

import java.time.LocalDate;

/**
 * How far ahead a forecast looks.
 *
 * <p>Pure, so the awkward parts — clamping the horizon, counting days — can be
 * tested without a database or a clock.
 */
public record ForecastWindow(LocalDate today, LocalDate end, int days) {

    /** Anything less is not a forecast, it is just today. */
    public static final int MIN_DAYS = 7;

    /**
     * Three months.
     *
     * <p>Past this, a monthly series has drifted so far that the arrival dates
     * are fiction. The forecast would still produce a number, and the number
     * would still look precise, which is the problem.
     */
    public static final int MAX_DAYS = 92;

    public static final int DEFAULT_DAYS = 30;

    public static ForecastWindow of(LocalDate today, int days) {
        int clamped = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        // Inclusive of today, so a 30-day window covers today plus 29 more.
        return new ForecastWindow(today, today.plusDays(clamped - 1L), clamped);
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(today) && !date.isAfter(end);
    }

    /** Where a date sits in the window, for indexing a running balance. */
    public int indexOf(LocalDate date) {
        return (int) (date.toEpochDay() - today.toEpochDay());
    }
}
