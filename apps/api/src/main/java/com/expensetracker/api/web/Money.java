package com.expensetracker.api.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Currency formatting for text the user reads.
 *
 * <p>Shared so that an amount quoted in a health suggestion and the same amount
 * quoted in a notification cannot be written differently. Only for prose —
 * anything the client might want to format itself is sent as a number.
 */
public final class Money {

    private Money() {
    }

    /**
     * Whole units only. The paise in "spend ₹4,213 less a month" are advice,
     * not accounting, and they make the sentence harder to read without making
     * it any truer.
     */
    public static String format(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.of("en", "IN"));
        try {
            format.setCurrency(Currency.getInstance(currency));
        } catch (RuntimeException ex) {
            // An unrecognised code must not take down the whole response.
            format.setCurrency(Currency.getInstance("INR"));
        }
        format.setMaximumFractionDigits(0);
        return format.format(amount.setScale(0, RoundingMode.HALF_UP));
    }
}
