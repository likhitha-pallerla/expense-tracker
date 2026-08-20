package com.expensetracker.api.dedup;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of comparing two transactions, including *why*.
 *
 * <p>{@code signals} is persisted on {@code duplicate_candidates.signals} so the
 * review UI can explain the match instead of showing an opaque number.
 */
public record DedupVerdict(double score, Decision decision, Map<String, Object> signals) {

    public enum Decision {
        /** Certain duplicate: same bank reference, or an overwhelming signal match. */
        AUTO_MERGE,
        /** Plausible duplicate: send to the review queue. */
        REVIEW,
        /** Independent transactions. */
        DISTINCT
    }

    public static DedupVerdict of(double score, Decision decision, Map<String, Object> signals) {
        return new DedupVerdict(score, decision, new LinkedHashMap<>(signals));
    }

    public boolean isDuplicate() {
        return decision != Decision.DISTINCT;
    }
}
