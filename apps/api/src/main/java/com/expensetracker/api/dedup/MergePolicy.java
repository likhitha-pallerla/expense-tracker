package com.expensetracker.api.dedup;

/**
 * Decides what to actually *do* with a {@link DedupVerdict}, given where the
 * two transactions came from.
 *
 * <p>{@link DedupEngine} weighs evidence; this weighs consequences. They are
 * separate because the cost of a mistake is not symmetric: wrongly merging two
 * real payments hides money the user spent, which is worse than asking a
 * question they did not need to answer.
 */
public final class MergePolicy {

    /** A transaction the user typed in themselves. */
    public static final String MANUAL = "manual";

    private MergePolicy() {
    }

    /**
     * Downgrades an automatic merge to a review when either side was entered by
     * hand.
     *
     * <p>Two identical purchases minutes apart at the same shop — a second
     * coffee, a repeated fare — score high enough to auto-merge. For rows
     * scraped from email and SMS that is the right call, because the same
     * payment genuinely does arrive twice. But a user who types a transaction
     * meant to type it, and silently folding it into another would delete
     * deliberate input they would struggle to notice was gone.
     *
     * <p>An identical bank reference is exempt: that is proof of identity, not
     * an inference, so it stands regardless of provenance.
     */
    public static DedupVerdict.Decision decide(
            DedupVerdict verdict, String providerA, String providerB) {

        if (verdict.decision() != DedupVerdict.Decision.AUTO_MERGE) {
            return verdict.decision();
        }

        if ("equal".equals(verdict.signals().get("externalRef"))) {
            return DedupVerdict.Decision.AUTO_MERGE;
        }

        boolean anyManual = MANUAL.equals(providerA) || MANUAL.equals(providerB);
        return anyManual ? DedupVerdict.Decision.REVIEW : DedupVerdict.Decision.AUTO_MERGE;
    }
}
