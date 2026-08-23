package com.expensetracker.api.goals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One deposit toward a goal, or one raid on it. */
public record GoalContribution(
        UUID id,
        BigDecimal amount,
        LocalDate occurredOn,
        String note,
        UUID transactionId,
        OffsetDateTime createdAt) {

    /**
     * Withdrawals are stored as negative amounts rather than as a separate
     * kind, so the running total is a plain sum and cannot be got wrong.
     */
    @JsonProperty("isWithdrawal")
    public boolean isWithdrawal() {
        return amount != null && amount.signum() < 0;
    }
}
