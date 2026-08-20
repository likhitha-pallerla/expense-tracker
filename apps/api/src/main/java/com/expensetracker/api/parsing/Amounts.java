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
 */
final class Amounts {

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
    static Optional<BigDecimal> parse(String text) {
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
}
