package com.expensetracker.api.sync;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What one sync attempt did, in terms the UI can repeat to the user.
 *
 * @param fetched how many messages the provider handed over
 * @param stored  how many were new. The gap between this and {@code fetched} is
 *                mail that was either not a payment alert or already held
 * @param skipped how many were already held. Kept apart from irrelevant mail so
 *                "we already had it" never reads as "we ignored it"
 * @param hasMore the provider still had more waiting, so pressing again will
 *                find something rather than nothing
 */
public record SyncRunView(
        UUID id,
        UUID connectionId,
        String provider,
        Instant startedAt,
        Instant finishedAt,
        String status,
        int fetched,
        int stored,
        int skipped,
        boolean hasMore,
        String error) {

    public boolean ok() {
        return "ok".equals(status);
    }

    /**
     * A sentence for the UI.
     *
     * <p>Built here rather than in the web app because the numbers only mean
     * something together: "3 new" and "3 fetched, 0 new" are the same run to a
     * careless reader and completely different to the user.
     *
     * <p>Annotated because Jackson serialises a record's components and nothing
     * else; without this the field simply would not appear, and the omission
     * would only show up as an empty line in the interface.
     */
    @JsonProperty("summary")
    public String summary() {
        if (!ok()) {
            return error == null ? "Could not check this mailbox." : error;
        }
        if (stored == 0 && skipped == 0) {
            return "Nothing new.";
        }
        if (stored == 0) {
            return "Nothing new — everything found was already imported.";
        }
        String found = stored == 1 ? "1 new alert" : stored + " new alerts";
        return hasMore ? found + " so far, with more still to check." : found + " imported.";
    }
}
