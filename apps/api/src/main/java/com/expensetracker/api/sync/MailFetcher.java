package com.expensetracker.api.sync;

import java.time.Instant;

import com.expensetracker.api.connections.MailProvider;

/**
 * Reads new mail from one provider.
 *
 * <p>Implementations are stateless: everything needed to resume is passed in
 * and everything learned is handed back. That is what makes a run interruptible
 * — the process can die halfway through a first sync of a decade-old mailbox
 * and the next run picks up from the last cursor that was actually written.
 */
public interface MailFetcher {

    MailProvider provider();

    /**
     * @param cursor       where the last run got to, or null to start fresh
     * @param backfillFrom how far back to reach when there is no cursor
     * @param budget       the most messages to fetch in this pass. A hard cap
     *                     rather than a target: free hosting kills a request
     *                     that runs long, and a run killed halfway has done its
     *                     work for nothing
     */
    FetchResult fetch(String accessToken, String cursor, Instant backfillFrom, int budget);
}
