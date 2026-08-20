package com.expensetracker.api.transactions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for a single expense or income row.
 *
 * <p>Transfers are not accepted here: a transfer is two linked legs and needs
 * both a source and a destination account, which this shape cannot express.
 * {@link TransferRequest} handles that case.
 */
public record TransactionRequest(
        String kind,

        String direction,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.00", message = "Amount cannot be negative")
        @Digits(integer = 12, fraction = 2, message = "Amount supports at most 2 decimal places")
        BigDecimal amount,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter code")
        String currency,

        @NotNull(message = "Date is required")
        Instant occurredAt,

        @Size(max = 500, message = "Description must be 500 characters or fewer")
        String description,

        @Size(max = 2000, message = "Notes must be 2000 characters or fewer")
        String notes,

        List<String> tags,

        UUID accountId,

        UUID categoryId,

        /** Free text; resolved to a merchant row via normalisation. */
        @Size(max = 200, message = "Merchant must be 200 characters or fewer")
        String merchant,

        /** Bank reference (RRN/UTR/auth code). Decisive for deduplication. */
        @Size(max = 200, message = "Reference must be 200 characters or fewer")
        String externalRef,

        Boolean isExcluded,

        Boolean isRecurring) {

    public TransactionKind resolvedKind() {
        TransactionKind resolved = TransactionKind.from(kind);
        if (resolved == TransactionKind.TRANSFER) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Use POST /api/transactions/transfers to record a transfer.");
        }
        return resolved;
    }

    public TransactionDirection resolvedDirection() {
        return direction == null || direction.isBlank()
                ? resolvedKind().defaultDirection()
                : TransactionDirection.from(direction);
    }

    public String currencyOrDefault() {
        return currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase();
    }

    public String[] tagsOrEmpty() {
        return tags == null ? new String[0] : tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .toArray(String[]::new);
    }

    /** Blank is normalised to null so the partial unique index ignores it. */
    public String externalRefOrNull() {
        return externalRef == null || externalRef.isBlank() ? null : externalRef.trim();
    }
}
