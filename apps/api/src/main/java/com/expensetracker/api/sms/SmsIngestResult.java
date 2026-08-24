package com.expensetracker.api.sms;

import com.expensetracker.api.parsing.ParseResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What became of an upload.
 *
 * <p>The counts exist to be shown to the user, not merely logged. An app that
 * reads text messages has to earn a lot of trust, and the way it earns it is by
 * being able to answer "what did you send?" precisely: of forty messages the
 * phone looked at, four were payments and were uploaded, nine were one-time
 * passwords, and twenty-seven were from people and never left the device. A
 * bare "synced" would tell the user nothing and would be indistinguishable from
 * an app that had uploaded everything.
 *
 * @param connectionId the {@code source_connection} representing this handset.
 * @param received     how many messages the request carried.
 * @param stored       how many were new and are now queued for parsing.
 * @param duplicates   how many the server had already seen, which is expected
 *                     and healthy on any rescan.
 * @param skipped      why the rest were refused, keyed by
 *                     {@link SmsFilter.Reason}. Reasons with a zero count are
 *                     omitted.
 * @param parsed       the outcome of reading the newly stored messages, or
 *                     {@code null} if parsing was deferred. Folded into this
 *                     response so a phone learns what its upload became in one
 *                     round trip rather than two.
 */
public record SmsIngestResult(
        UUID connectionId,
        int received,
        int stored,
        int duplicates,
        Map<String, Integer> skipped,
        ParseResult parsed) {

    /** Accumulates a result while a batch is walked. */
    static final class Builder {

        private final UUID connectionId;
        private int received;
        private int stored;
        private int duplicates;
        private final Map<SmsFilter.Reason, Integer> skipped = new LinkedHashMap<>();

        Builder(UUID connectionId) {
            this.connectionId = connectionId;
        }

        void counted() {
            received++;
        }

        void storedOne() {
            stored++;
        }

        int stored() {
            return stored;
        }

        /**
         * A message the database already held.
         *
         * <p>Counted apart from {@link #skipped}, because the two mean opposite
         * things. A duplicate is the system working — the same alert reaching
         * us by two routes, collapsing into one. A skip is a message we chose
         * not to keep.
         */
        void duplicate() {
            duplicates++;
        }

        void skipped(SmsFilter.Reason reason) {
            skipped.merge(reason, 1, Integer::sum);
        }

        SmsIngestResult build(ParseResult parsed) {
            Map<String, Integer> reasons = new LinkedHashMap<>();
            skipped.forEach((reason, count) -> reasons.put(reason.name(), count));
            return new SmsIngestResult(connectionId, received, stored, duplicates, reasons, parsed);
        }
    }
}
