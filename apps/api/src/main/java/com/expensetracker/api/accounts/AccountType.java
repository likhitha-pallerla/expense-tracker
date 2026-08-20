package com.expensetracker.api.accounts;

import java.util.Arrays;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Mirrors the {@code account_type} Postgres enum. */
public enum AccountType {
    BANK,
    CASH,
    UPI,
    WALLET,
    CREDIT_CARD,
    OTHER;

    /** The lowercase form Postgres stores. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses client input, rejecting unknown values with a 400 rather than
     * letting Postgres fail the insert with an opaque enum cast error.
     */
    public static AccountType from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account type is required");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unknown account type '%s'. Expected one of %s."
                                .formatted(raw, Arrays.stream(values()).map(AccountType::dbValue).toList())));
    }
}
