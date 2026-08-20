package com.expensetracker.api.transactions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Moving money between two of the user's own accounts.
 *
 * <p>Recorded as two legs sharing a {@code transfer_id}: a debit from the
 * source and a credit to the destination. Both accounts show the movement, and
 * because the legs cancel out, a transfer never inflates spending totals.
 */
public record TransferRequest(
        @NotNull(message = "Source account is required")
        UUID fromAccountId,

        @NotNull(message = "Destination account is required")
        UUID toAccountId,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount supports at most 2 decimal places")
        BigDecimal amount,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter code")
        String currency,

        @NotNull(message = "Date is required")
        Instant occurredAt,

        @Size(max = 500, message = "Description must be 500 characters or fewer")
        String description,

        @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
        String notes) {

    public String currencyOrDefault() {
        return currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase();
    }

    public String descriptionOrDefault() {
        return description == null || description.isBlank() ? "Transfer" : description.trim();
    }
}
