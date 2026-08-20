package com.expensetracker.api.sync;

import java.util.List;

/**
 * What one pass over a mailbox produced.
 *
 * @param messages     everything fetched, before relevance filtering. The
 *                     filter belongs to the caller so both providers are judged
 *                     by the same rule
 * @param nextCursor   where to resume. Never null on success — a run that
 *                     cannot say where it got to would make the next run start
 *                     from the beginning
 * @param hasMore      the provider still had more waiting. A run stops on a
 *                     budget rather than on exhaustion, so this is normal for a
 *                     first sync of a busy mailbox and the caller is expected
 *                     to come back
 * @param cursorReset  the resume point had expired and this pass fell back to
 *                     scanning by date. Worth recording: it explains a run that
 *                     re-read messages it had already stored
 */
public record FetchResult(
        List<MailMessage> messages,
        String nextCursor,
        boolean hasMore,
        boolean cursorReset) {

    public FetchResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static FetchResult empty(String cursor) {
        return new FetchResult(List.of(), cursor, false, false);
    }
}
