package com.expensetracker.api.goals;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Money going into a goal.
 *
 * @param amount     negative to record taking money back out; zero is rejected
 *                   because it records nothing
 * @param occurredOn defaults to today in the user's timezone, not the server's
 */
public record GoalContributionRequest(
        @NotNull(message = "Amount is required")
        @Digits(integer = 12, fraction = 2, message = "Amount supports at most 2 decimal places")
        BigDecimal amount,

        LocalDate occurredOn,

        @Size(max = 500, message = "Note must be 500 characters or fewer")
        String note) {

    public LocalDate resolvedDate(LocalDate today) {
        return occurredOn == null ? today : occurredOn;
    }
}
