package com.expensetracker.api.parsing;

import java.time.Instant;
import java.util.UUID;

/**
 * A message we could not turn into a transaction.
 *
 * <p>The snippet is included because the reason alone is rarely enough: "could
 * not find the amount" makes sense only next to the message it came from. It is
 * the same short preview the mail provider itself shows in an inbox list, not
 * the full body.
 *
 * @param ruleName the rule that matched, or null when none did
 * @param reason   what was missing, in words the user can act on
 */
public record UnreadMessage(
        UUID id,
        String subject,
        String sender,
        Instant receivedAt,
        String ruleName,
        String reason,
        String snippet) {
}
