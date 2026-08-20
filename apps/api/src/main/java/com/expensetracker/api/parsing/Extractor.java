package com.expensetracker.api.parsing;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One field pulled out of an alert.
 *
 * @param pattern where to look
 * @param group   which capture group holds the answer
 * @param as      how to read what was captured
 */
record Extractor(Pattern pattern, int group, Kind as) {

    enum Kind {
        /** A sum of money, in whatever grouping the bank likes. */
        AMOUNT,
        /** A word like "debited" or "credited". */
        DIRECTION,
        /** A date fragment with no time. */
        DATE,
        /** Anything else: a merchant, a reference, four digits of an account. */
        TEXT
    }

    /**
     * @return the captured text, or null when the pattern does not match or the
     *         group is empty. Null means "not present", which callers treat
     *         very differently from a bad value.
     */
    String capture(CharSequence text) {
        try {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find() || matcher.groupCount() < group) {
                return null;
            }
            String value = matcher.group(group);
            return value == null || value.isBlank() ? null : value.strip();
        } catch (Bounded.BudgetExceeded ex) {
            // A rule that cannot finish is a rule that did not match. Failing
            // the whole parse here would let one bad pattern block every
            // message rather than just its own field.
            return null;
        }
    }
}
