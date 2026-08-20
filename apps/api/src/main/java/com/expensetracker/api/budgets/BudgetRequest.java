package com.expensetracker.api.budgets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A spending limit the user sets.
 *
 * @param categoryId      null means an overall budget covering every expense
 * @param period          weekly, monthly or yearly; defaults to monthly
 * @param startsOn        the day the first period begins; windows follow it
 * @param rollover        carry unspent allowance into the next period
 * @param alertThresholds percentages at which to warn, e.g. {@code [50, 80, 100]}
 */
public record BudgetRequest(
        @Size(max = 120, message = "Name must be 120 characters or fewer")
        String name,

        UUID categoryId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "A budget has to be more than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount supports at most 2 decimal places")
        BigDecimal amount,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter code")
        String currency,

        String period,

        LocalDate startsOn,

        LocalDate endsOn,

        Boolean rollover,

        List<Integer> alertThresholds,

        Boolean isActive) {

    /** Thresholds users actually recognise: half spent, nearly gone, and gone. */
    private static final List<Integer> DEFAULT_THRESHOLDS = List.of(50, 80, 100);

    public BudgetPeriod resolvedPeriod() {
        return BudgetPeriod.from(period);
    }

    public LocalDate resolvedStartsOn(LocalDate today) {
        return startsOn == null ? today : startsOn;
    }

    public String currencyOrDefault(String fallback) {
        return currency == null || currency.isBlank()
                ? fallback
                : currency.toUpperCase(java.util.Locale.ROOT);
    }

    public boolean rolloverOrDefault() {
        return Boolean.TRUE.equals(rollover);
    }

    public boolean activeOrDefault() {
        return isActive == null || isActive;
    }

    /**
     * Thresholds, cleaned up.
     *
     * <p>Sorted and de-duplicated so the UI can walk them in order, and capped
     * at 500% because the column is a {@code smallint} — an absurd value would
     * fail at the database with an unreadable error.
     */
    public List<Integer> resolvedThresholds() {
        if (alertThresholds == null || alertThresholds.isEmpty()) {
            return DEFAULT_THRESHOLDS;
        }
        List<Integer> cleaned = alertThresholds.stream()
                .filter(java.util.Objects::nonNull)
                .filter(value -> value > 0 && value <= 500)
                .distinct()
                .sorted()
                .toList();
        return cleaned.isEmpty() ? DEFAULT_THRESHOLDS : cleaned;
    }
}
