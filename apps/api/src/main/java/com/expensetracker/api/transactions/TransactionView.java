package com.expensetracker.api.transactions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A transaction as returned to clients, with the display fields the list view
 * needs joined in so the UI does not have to resolve ids itself.
 */
public record TransactionView(
        UUID id,
        String kind,
        String direction,
        BigDecimal amount,
        BigDecimal signedAmount,
        String currency,
        Instant occurredAt,
        String description,
        String notes,
        List<String> tags,

        UUID accountId,
        String accountName,
        UUID categoryId,
        String categoryName,
        UUID merchantId,
        String merchantName,

        UUID transferId,
        UUID counterpartAccountId,
        String counterpartAccountName,

        String externalRef,
        UUID mergedIntoId,
        String source,
        boolean isExcluded,
        boolean isRecurring,
        Instant createdAt) {
}
