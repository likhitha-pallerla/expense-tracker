package com.expensetracker.api.notifications;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An alert together with what the user has already done about it.
 *
 * <p>Everything except {@code read} and {@code dismissed} is derived on read.
 * Those two are the only things a person can tell us that the ledger cannot.
 */
public record NotificationView(
        String key,
        String type,
        String severity,
        String title,
        String body,
        String href,
        LocalDate occurredOn,
        boolean read,
        boolean dismissed,
        Instant readAt,
        Instant dismissedAt) {
}
