package com.expensetracker.api.goals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A goal the user is saving toward.
 *
 * @param targetDate    optional; without it the goal has progress but no
 *                      deadline, and nothing may describe it as late
 * @param monthlyTarget optional; what they intend to put aside each month,
 *                      which is a separate thing from what the date requires
 * @param accountId     optional; where the money is being kept, for reference
 * @param status        active, achieved, paused or cancelled
 */
public record GoalRequest(
        @NotBlank(message = "Give the goal a name")
        @Size(max = 120, message = "Name must be 120 characters or fewer")
        String name,

        @NotNull(message = "Target amount is required")
        @DecimalMin(value = "0.01", message = "A goal has to be more than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount supports at most 2 decimal places")
        BigDecimal targetAmount,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter code")
        String currency,

        LocalDate targetDate,

        @DecimalMin(value = "0.01", message = "A monthly plan has to be more than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount supports at most 2 decimal places")
        BigDecimal monthlyTarget,

        UUID accountId,

        @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
        String notes,

        String status) {

    public String currencyOrDefault(String fallback) {
        return currency == null || currency.isBlank()
                ? fallback
                : currency.toUpperCase(Locale.ROOT);
    }

    public GoalStatus resolvedStatus() {
        return GoalStatus.from(status);
    }
}
