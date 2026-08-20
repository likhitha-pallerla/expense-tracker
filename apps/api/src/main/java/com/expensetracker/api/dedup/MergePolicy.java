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

    private MergePolicy() {
    }

    /**
     * Adjusts a verdict for provenance.
     *
     * <p>Two rules override the score:
     *
     * <p><b>Rows from the same uploaded file are never duplicates.</b> A bank
     * statement lists each payment exactly once, so two matching lines in one
     * file are two real payments — a coffee bought twice, a fare paid twice.
     * Merging them would delete money the user actually spent, and queueing
     * them would bury the user in questions after every import.
     *
     * <p><b>Hand-typed transactions are never merged automatically.</b> Two
     * identical purchases minutes apart score high enough to auto-merge, which
     * is right for rows scraped from email and SMS, because the same payment
     * genuinely does arrive twice. But a user who types a transaction meant to,
     * and silently folding it into another would destroy deliberate input they
     * would struggle to notice was gone. Those go to review instead.
     *
     * <p>An identical bank reference is exempt from the second rule: that is
     * proof of identity rather than an inference.
     */
    public static DedupVerdict.Decision decide(
            DedupVerdict verdict, Provenance a, Provenance b) {

        if (a.sameImportAs(b)) {
            return DedupVerdict.Decision.DISTINCT;
        }

        if (verdict.decision() != DedupVerdict.Decision.AUTO_MERGE) {
            return verdict.decision();
        }

        if ("equal".equals(verdict.signals().get("externalRef"))) {
            return DedupVerdict.Decision.AUTO_MERGE;
        }

        return a.isManual() || b.isManual()
                ? DedupVerdict.Decision.REVIEW
                : DedupVerdict.Decision.AUTO_MERGE;
    }
}
