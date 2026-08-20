package com.expensetracker.api.connections;

import java.time.Instant;
import java.util.UUID;

/**
 * A linked mailbox as the UI sees it.
 *
 * <p>Conspicuously absent: anything token-shaped. Neither the encrypted
 * envelope nor a truncated hint of it leaves the server, because a value that
 * is never sent cannot be leaked by a screenshot, a browser extension, or a
 * support ticket with the network tab open.
 */
public record ConnectionView(
        UUID id,
        String provider,
        String label,
        String address,
        String status,
        String statusDetail,
        Instant connectedAt,
        Instant lastSyncedAt,
        String lastError,
        boolean needsReauth) {
}
