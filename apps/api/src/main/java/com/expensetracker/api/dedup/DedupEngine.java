package com.expensetracker.api.dedup;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.expensetracker.api.merchants.MerchantNormalizer;

/**
 * Decides whether two transactions describe the same real-world payment.
 *
 * <p>This is the layer that makes automatic ingestion usable: the same purchase
 * routinely arrives twice, once as a bank email and once as an SMS alert. It is
 * deliberately free of database and framework dependencies so every rule can be
 * unit tested.
 *
 * <h2>Layers</h2>
 * <ul>
 *   <li><b>L0</b> — identical raw message. Enforced by unique constraints on
 *       {@code raw_messages}; never reaches this class.</li>
 *   <li><b>L1</b> — bank reference (RRN/UTR/auth code). Decisive in both
 *       directions: equal refs mean the same payment, different refs mean
 *       genuinely different payments.</li>
 *   <li><b>L2</b> — weighted fuzzy signals, implemented here.</li>
 *   <li><b>L3</b> — the user resolves anything landing in {@link
 *       DedupVerdict.Decision#REVIEW}.</li>
 * </ul>
 */
public final class DedupEngine {

    /** At or above this score the pair is merged without asking. */
    public static final double AUTO_MERGE_THRESHOLD = 0.90;

    /** At or above this score the pair goes to the review queue. */
    public static final double REVIEW_THRESHOLD = 0.55;

    /**
     * Beyond this gap two identical amounts are treated as separate purchases.
     * Generous because bank emails can lag the SMS alert by a day or more.
     */
    public static final Duration MAX_WINDOW = Duration.ofHours(72);

    // Weights sum to 1.0.
    private static final double W_TIME = 0.40;
    private static final double W_MERCHANT = 0.35;
    private static final double W_ACCOUNT = 0.15;
    private static final double W_SOURCE = 0.10;

    private DedupEngine() {
    }

    public static DedupVerdict compare(DedupCandidate a, DedupCandidate b) {
        Map<String, Object> signals = new LinkedHashMap<>();

        if (a.id() != null && a.id().equals(b.id())) {
            throw new IllegalArgumentException("Cannot compare a transaction with itself");
        }

        // ---- L1: bank reference is authoritative in both directions --------
        String refA = blankToNull(a.externalRef());
        String refB = blankToNull(b.externalRef());

        if (refA != null && refB != null) {
            if (refA.equalsIgnoreCase(refB)) {
                signals.put("externalRef", "equal");
                return DedupVerdict.of(1.0, DedupVerdict.Decision.AUTO_MERGE, signals);
            }
            // Two distinct bank references cannot be one payment, no matter how
            // similar everything else looks.
            signals.put("externalRef", "different");
            return DedupVerdict.of(0.0, DedupVerdict.Decision.DISTINCT, signals);
        }

        // ---- Hard gates ----------------------------------------------------
        if (!a.sameDirection(b)) {
            signals.put("reject", "direction");
            return DedupVerdict.of(0.0, DedupVerdict.Decision.DISTINCT, signals);
        }

        if (!a.sameCurrency(b)) {
            signals.put("reject", "currency");
            return DedupVerdict.of(0.0, DedupVerdict.Decision.DISTINCT, signals);
        }

        if (a.amount() == null || b.amount() == null || a.amount().compareTo(b.amount()) != 0) {
            signals.put("reject", "amount");
            return DedupVerdict.of(0.0, DedupVerdict.Decision.DISTINCT, signals);
        }
        signals.put("amount", "equal");

        Duration gap = absGap(a, b);
        if (gap == null || gap.compareTo(MAX_WINDOW) > 0) {
            signals.put("reject", "timeWindow");
            return DedupVerdict.of(0.0, DedupVerdict.Decision.DISTINCT, signals);
        }

        // ---- L2: weighted signals -----------------------------------------
        double timeScore = timeScore(gap);
        double merchantScore = merchantScore(a, b);
        double accountScore = accountScore(a, b);
        double sourceScore = sourceScore(a, b);

        signals.put("minutesApart", gap.toMinutes());
        signals.put("timeScore", round(timeScore));
        signals.put("merchantScore", round(merchantScore));
        signals.put("accountScore", round(accountScore));
        signals.put("sourceScore", round(sourceScore));

        double score = round(
                W_TIME * timeScore
                        + W_MERCHANT * merchantScore
                        + W_ACCOUNT * accountScore
                        + W_SOURCE * sourceScore);

        signals.put("score", score);

        DedupVerdict.Decision decision;
        if (score >= AUTO_MERGE_THRESHOLD) {
            decision = DedupVerdict.Decision.AUTO_MERGE;
        } else if (score >= REVIEW_THRESHOLD) {
            decision = DedupVerdict.Decision.REVIEW;
        } else {
            decision = DedupVerdict.Decision.DISTINCT;
        }

        return DedupVerdict.of(score, decision, signals);
    }

    private static Duration absGap(DedupCandidate a, DedupCandidate b) {
        if (a.occurredAt() == null || b.occurredAt() == null) {
            return null;
        }
        return Duration.between(a.occurredAt(), b.occurredAt()).abs();
    }

    /**
     * Decays with the gap. The same payment reported twice is usually minutes
     * apart; a day apart is far more likely to be a genuine repeat purchase.
     */
    private static double timeScore(Duration gap) {
        long minutes = gap.toMinutes();
        if (minutes <= 2) {
            return 1.0;
        } else if (minutes <= 15) {
            return 0.95;
        } else if (minutes <= 60) {
            return 0.85;
        } else if (minutes <= 360) {
            return 0.60;
        } else if (minutes <= 1440) {
            return 0.35;
        }
        return 0.15;
    }

    private static double merchantScore(DedupCandidate a, DedupCandidate b) {
        if (a.merchantId() != null && a.merchantId().equals(b.merchantId())) {
            return 1.0;
        }

        String left = MerchantNormalizer.normalize(a.normalizedMerchant());
        String right = MerchantNormalizer.normalize(b.normalizedMerchant());

        if (left == null || right == null) {
            // One side is an unlabelled bank alert. Neutral rather than
            // penalising, since missing data is not evidence of difference.
            return 0.5;
        }

        return MerchantNormalizer.similarity(left, right);
    }

    private static double accountScore(DedupCandidate a, DedupCandidate b) {
        if (a.accountId() == null || b.accountId() == null) {
            return 0.5;
        }
        return a.accountId().equals(b.accountId()) ? 1.0 : 0.0;
    }

    /**
     * Two reports of one payment typically arrive on different channels (email
     * and SMS). Two identical charges from the same channel are more likely to
     * be genuine repeats.
     */
    private static double sourceScore(DedupCandidate a, DedupCandidate b) {
        if (a.sourceProvider() == null || b.sourceProvider() == null) {
            return 0.5;
        }
        return a.sourceProvider().equals(b.sourceProvider()) ? 0.0 : 1.0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
