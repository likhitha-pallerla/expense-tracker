package com.expensetracker.api.parsing;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What one pass over the waiting messages did.
 *
 * @param read        how many messages were looked at
 * @param imported    how many became new transactions
 * @param merged      how many turned out to be a payment already recorded, most
 *                    often the same payment reported by both mail and SMS
 * @param ignored     how many were set aside as not being payments
 * @param failed      how many could not be read, and are listed for the user
 * @param quarantined how many came from a sender that could not be established
 *                    as a bank, and are waiting for the user to say
 */
public record ParseResult(int read, int imported, int merged, int ignored, int failed,
        int quarantined) {

    /**
     * A sentence for the interface.
     *
     * <p>Annotated because Jackson serialises a record's components and nothing
     * else; without this the field would simply be absent and the interface
     * would show an empty line.
     */
    @JsonProperty("summary")
    public String summary() {
        if (read == 0) {
            return "No new alerts to read.";
        }
        StringBuilder text = new StringBuilder();
        text.append(imported == 1 ? "1 transaction added" : imported + " transactions added");

        // Merges are worth saying out loud. Without this the numbers do not add
        // up on screen and it looks like alerts were lost.
        if (merged > 0) {
            text.append(merged == 1
                    ? ", 1 was already recorded"
                    : ", " + merged + " were already recorded");
        }
        if (failed > 0) {
            text.append(failed == 1
                    ? ", 1 could not be read"
                    : ", " + failed + " could not be read");
        }

        // Last, and always mentioned. These are messages that looked like
        // payments and were deliberately not acted on; silence would read as
        // the alerts having been lost.
        if (quarantined > 0) {
            text.append(quarantined == 1
                    ? ", 1 is waiting for you to confirm the sender"
                    : ", " + quarantined + " are waiting for you to confirm the sender");
        }
        return text.append(".").toString();
    }
}
