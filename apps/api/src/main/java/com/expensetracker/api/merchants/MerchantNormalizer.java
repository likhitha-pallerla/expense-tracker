package com.expensetracker.api.merchants;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collapses the noise banks wrap around merchant names.
 *
 * <p>Mirrors the SQL {@code normalize_merchant_name} function so the same
 * string normalises identically whether it is produced in Java or in Postgres.
 * Any change here must be mirrored in a new migration, and vice versa.
 */
public final class MerchantNormalizer {

    /**
     * Payment-rail noise and corporate suffixes, neither of which carries
     * merchant identity. Without the suffixes, "SWIGGY" and "SWIGGY LTD" score
     * as a half match and a genuine email+SMS duplicate escapes auto-merge.
     */
    private static final Pattern NOISE = Pattern.compile(
            "(^|[^A-Z0-9])(UPI|POS|ATM|NEFT|IMPS|RTGS|ACH|MMT|VPA|TXN|REF|PURCHASE|PAYMENT|PMT"
                    + "|LTD|LIMITED|PVT|PRIVATE|INC|LLP|LLC|CORP|COMPANY)([^A-Z0-9]|$)");

    private static final Pattern NON_LETTERS = Pattern.compile("[^A-Z ]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MerchantNormalizer() {
    }

    /** Returns the canonical form, or null when nothing meaningful remains. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }

        String value = raw.toUpperCase(Locale.ROOT);

        // Applied repeatedly because adjacent noise tokens share a delimiter,
        // so a single pass leaves every second token behind ("UPI POS SWIGGY").
        String previous;
        do {
            previous = value;
            value = NOISE.matcher(value).replaceAll(" ");
        } while (!value.equals(previous));

        value = NON_LETTERS.matcher(value).replaceAll(" ");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();

        return value.isEmpty() ? null : value;
    }

    /** Token set used for similarity scoring. */
    public static Set<String> tokens(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(normalized.split(" ")));
    }

    /**
     * Token-set similarity in [0,1].
     *
     * <p>Blends Jaccard with the overlap coefficient so that a strict subset
     * ("SWIGGY" inside "SWIGGY INSTAMART") scores highly. Plain Jaccard
     * penalises extra descriptive tokens so heavily that real duplicates fall
     * below the merge threshold. Chosen over edit distance because bank strings
     * differ by whole tokens far more often than by characters.
     */
    public static double similarity(String a, String b) {
        Set<String> left = tokens(a);
        Set<String> right = tokens(b);

        if (left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (left.equals(right)) {
            return 1.0;
        }

        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);

        if (intersection.isEmpty()) {
            return 0.0;
        }

        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);

        double jaccard = (double) intersection.size() / union.size();
        double overlap = (double) intersection.size() / Math.min(left.size(), right.size());

        // Capped below 1.0 so a containment match never outranks an exact one.
        return Math.max(jaccard, 0.9 * overlap);
    }
}
