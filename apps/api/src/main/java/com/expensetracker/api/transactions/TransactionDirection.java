package com.expensetracker.api.transactions;

import java.util.Arrays;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mirrors the {@code transaction_direction} Postgres enum.
 *
 * <p>Direction carries the sign of the money movement; {@code amount} itself is
 * always positive. Keeping the sign in a separate column means a mistyped
 * minus can never silently turn an expense into income.
 */
public enum TransactionDirection {
    DEBIT,
    CREDIT;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public TransactionDirection opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    public static TransactionDirection from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Direction is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(direction -> direction.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown direction '%s'. Expected one of %s."
                                .formatted(raw,
                                        Arrays.stream(values()).map(TransactionDirection::dbValue).toList())));
    }
}
