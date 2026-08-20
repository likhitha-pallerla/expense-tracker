package com.expensetracker.api.parsing;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A compiled rule for reading one shape of payment alert.
 *
 * <p>Rules come from the database rather than from Java because bank formats
 * change without notice and differ by issuer. A rule with a null
 * {@code userId} is built in and shared; one with a userId belongs to a single
 * person and always wins, because they know their own bank better than we do.
 *
 * @param senderPattern optional restriction on who sent the mail. Left null on
 *                      the built-in rules deliberately: matching on sender
 *                      would mean shipping a list of every bank domain in
 *                      India, and getting it wrong silently drops mail
 * @param matchPattern  whether this rule applies at all
 * @param extractors    field name to how that field is found
 * @param priority      lower is tried first
 */
record ParserRule(
        UUID id,
        UUID userId,
        String name,
        Pattern senderPattern,
        Pattern matchPattern,
        Map<String, Extractor> extractors,
        int priority) {

    static final String AMOUNT = "amount";
    static final String DIRECTION = "direction";
    static final String MERCHANT = "merchant";
    static final String LAST4 = "last4";
    static final String OCCURRED_AT = "occurredAt";
    static final String REFERENCE = "reference";

    boolean isBuiltIn() {
        return userId == null;
    }

    /** True when this rule is willing to try the message at all. */
    boolean appliesTo(String sender, CharSequence text) {
        try {
            if (senderPattern != null
                    && (sender == null || !senderPattern.matcher(sender).find())) {
                return false;
            }
            return matchPattern.matcher(text).find();
        } catch (Bounded.BudgetExceeded ex) {
            return false;
        }
    }

    Extractor extractor(String field) {
        return extractors.get(field);
    }
}
