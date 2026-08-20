package com.expensetracker.api.dedup;

import java.util.UUID;

/**
 * Where a transaction came from, which decides how much the dedup score is
 * allowed to act on its own.
 *
 * @param provider      the channel: {@code gmail}, {@code android_sms},
 *                      {@code csv_import}, or {@code manual}
 * @param importBatchId the CSV import this row arrived in, if any
 */
public record Provenance(String provider, UUID importBatchId) {

    public static final String MANUAL = "manual";

    public static Provenance manual() {
        return new Provenance(MANUAL, null);
    }

    public boolean isManual() {
        return MANUAL.equals(provider);
    }

    /** True when both rows came from the same uploaded file. */
    public boolean sameImportAs(Provenance other) {
        return importBatchId != null && importBatchId.equals(other.importBatchId);
    }
}
