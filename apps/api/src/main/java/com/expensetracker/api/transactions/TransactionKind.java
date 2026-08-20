package com.expensetracker.api.transactions;

import java.util.Arrays;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Mirrors the {@code transaction_kind} Postgres enum. */
public enum TransactionKind {
    EXPENSE,
    INCOME,
    TRANSFER;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The direction implied by the kind, used when a client omits it. */
    public TransactionDirection defaultDirection() {
        return this == INCOME ? TransactionDirection.CREDIT : TransactionDirection.DEBIT;
    }

    public static TransactionKind from(String raw) {
        if (raw == null || raw.isBlank()) {
            return EXPENSE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(kind -> kind.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown kind '%s'. Expected one of %s."
                                .formatted(raw, Arrays.stream(values()).map(TransactionKind::dbValue).toList())));
    }
}
