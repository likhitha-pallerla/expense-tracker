package com.expensetracker.api.parsing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Reading the money out of an alert.
 *
 * <p>Indian bank alerts group digits by lakh — {@code 1,23,456.78} rather than
 * {@code 123,456.78} — so no locale-aware parser can be trusted here. Stripping
 * the separators and parsing what is left is both simpler and correct for every
 * grouping convention at once.
 *
 * <p>Public because natural-language entry needs exactly the same reading of
 * exactly the same conventions. Two copies of this would drift, and the pair
 * that drifted would disagree about somebody's money.
 */
public final class Amounts {

    /**
     * Above this, the "amount" is almost certainly something else that happened
     * to sit next to a currency symbol: an account number, a reward-point
     * balance, a phone number. A wrong transaction of this size would distort
     * every chart on the dashboard, so it is rejected rather than guessed at.
     */
    private static final BigDecimal MAX = new BigDecimal("100000000");

    private Amounts() {
    }

    /**
     * @return the amount, or empty when the text is not a usable figure. Zero is
     *         rejected: a zero-rupee alert is a notification, not a payment.
     */
    public static Optional<BigDecimal> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }

        // Separators only between digits. A trailing comma is punctuation from
        // the sentence around the number, not part of it.
        String cleaned = text.strip().replaceAll("(?<=\\d)[,\\s](?=\\d)", "");
        if (!cleaned.matches("\\d+(\\.\\d{1,2})?")) {
            return Optional.empty();
        }

        BigDecimal amount = new BigDecimal(cleaned).setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() <= 0 || amount.compareTo(MAX) > 0) {
            return Optional.empty();
        }
        return Optional.of(amount);
    }

    /**
     * Reads a figure that may still be carrying its currency marker.
     *
     * <p>{@link #parse} is fed by regular-expression capture groups, which have
     * already isolated the digits, so it insists on a bare number. A figure
     * typed by a person or returned by a model has not been through that step
     * and arrives as {@code "Rs. 1,250"} or {@code "₹250"} or {@code "1250
     * INR"}.
     *
     * <p>Only the marker is removed. Anything else around the number still
     * fails, which is the point: {@code "about 500"} is a model telling you it
     * does not know the amount, and turning that into a transaction for 500
     * would be recording a guess as a fact.
     */
    public static Optional<BigDecimal> parseWithCurrency(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String stripped = text.strip()
                .replaceAll("(?i)^(rs\\.?|inr|rupees?|₹|\\$)\\s*", "")
                .replaceAll("(?i)\\s*(rs\\.?|inr|rupees?|₹|\\$)$", "");
        return parse(stripped);
    }
}
