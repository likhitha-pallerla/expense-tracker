package com.expensetracker.api.notifications;

import jakarta.validation.constraints.NotBlank;

/**
 * Addresses an alert by key rather than by id.
 *
 * <p>A derived alert has no row until the user acts on it, so there is no id to
 * quote. Keys also travel in the request body rather than the path because they
 * contain colons and merchant names, which have no business being URL-encoded
 * into a route.
 */
public record NotificationRequest(@NotBlank String key) {
}
