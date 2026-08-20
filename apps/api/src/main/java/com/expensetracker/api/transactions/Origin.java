package com.expensetracker.api.transactions;

import java.util.UUID;

/**
 * Where a transaction came from, when it did not come from a person typing it.
 *
 * <p>This has to be set at insert time rather than patched on afterwards.
 * Deduplication runs immediately after the insert and its decision depends on
 * provenance — two rows from the same statement are two payments, while a mail
 * alert and a statement row are usually one payment reported twice. A row that
 * was still anonymous when it was screened would be judged by the wrong rule.
 *
 * @param importBatchId the CSV upload this arrived in, if any
 * @param sourceId      the mailbox or device connection it came from, if any
 * @param rawMessageId  the stored message it was read from, if any. Also what
 *                      stops a second parse creating a second transaction
 */
public record Origin(UUID importBatchId, UUID sourceId, UUID rawMessageId) {

    public static final Origin MANUAL = new Origin(null, null, null);

    public static Origin csvImport(UUID importBatchId) {
        return new Origin(importBatchId, null, null);
    }

    public static Origin message(UUID sourceId, UUID rawMessageId) {
        return new Origin(null, sourceId, rawMessageId);
    }
}
