package com.expensetracker.api.budgets;

import java.time.LocalDate;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** How often a budget resets. Mirrors the {@code budget_period} enum. */
public enum BudgetPeriod {

    WEEKLY,
    MONTHLY,
    YEARLY;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BudgetPeriod from(String value) {
        if (value == null || value.isBlank()) {
            return MONTHLY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Period must be weekly, monthly or yearly.");
        }
    }

    /**
     * Moves a date forward by {@code count} periods.
     *
     * <p>Always measured from the budget's original start date rather than
     * stepped one period at a time. A budget starting on the 31st would
     * otherwise drift: the 31st clamps to the 28th in February, and stepping
     * on from there would leave every later month stuck on the 28th.
     */
    public LocalDate advance(LocalDate from, long count) {
        return switch (this) {
            case WEEKLY -> from.plusWeeks(count);
            case MONTHLY -> from.plusMonths(count);
            case YEARLY -> from.plusYears(count);
        };
    }

    public String label() {
        return switch (this) {
            case WEEKLY -> "week";
            case MONTHLY -> "month";
            case YEARLY -> "year";
        };
    }
}
