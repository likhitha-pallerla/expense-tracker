package com.expensetracker.api.accounts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An account as returned to clients.
 *
 * <p>{@code balance} is derived by {@code account_balance()} rather than stored,
 * so it cannot drift when transactions are edited, deleted or merged.
 */
public record AccountView(
        UUID id,
        String name,
        String type,
        String currency,
        String last4,
        BigDecimal openingBalance,
        BigDecimal balance,
        boolean isArchived,
        int sortOrder,
        Instant createdAt) {
}
