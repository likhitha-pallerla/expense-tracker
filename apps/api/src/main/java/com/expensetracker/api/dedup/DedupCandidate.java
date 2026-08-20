package com.expensetracker.api.dedup;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The minimal projection of a transaction needed to decide whether two rows
 * describe the same real-world payment.
 *
 * <p>Deliberately decoupled from persistence so the scoring rules can be
 * exercised exhaustively in unit tests without a database.
 */
public record DedupCandidate(
        UUID id,
        BigDecimal amount,
        String currency,
        String direction,
        Instant occurredAt,
        UUID accountId,
        UUID merchantId,
        String normalizedMerchant,
        String externalRef,
        String sourceProvider) {

    public boolean sameDirection(DedupCandidate other) {
        return direction != null && direction.equals(other.direction);
    }

    public boolean sameCurrency(DedupCandidate other) {
        return currency != null && currency.equalsIgnoreCase(other.currency);
    }
}
