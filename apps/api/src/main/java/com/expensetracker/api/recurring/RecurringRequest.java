package com.expensetracker.api.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

/**
 * Saving a recurring payment, either by confirming a detected one or by
 * entering it by hand.
 *
 * <p>Both go through the same shape on purpose. A subscription the user typed
 * in before its first charge should behave exactly like one that was detected,
 * including picking up real charges once they start arriving.
 */
public record RecurringRequest(
        String matchKey,

        @Size(max = 120, message = "Keep the name under 120 characters")
        String name,

        UUID categoryId,
        UUID accountId,

        @DecimalMin(value = "0.01", message = "The amount has to be more than zero")
        @Digits(integer = 12, fraction = 2, message = "That is not a valid amount")
        BigDecimal amount,

        @Size(min = 3, max = 3, message = "Use a three letter currency code")
        String currency,

        String cadence,
        LocalDate nextExpected,
        String direction,
        Boolean isSubscription,
        Boolean isActive,

        @Size(max = 500, message = "Keep notes under 500 characters")
        String notes) {

    public String trimmedName() {
        return name == null || name.isBlank() ? null : name.trim();
    }

    public String trimmedMatchKey() {
        return matchKey == null || matchKey.isBlank() ? null : matchKey.trim();
    }

    public String trimmedNotes() {
        return notes == null || notes.isBlank() ? null : notes.trim();
    }

    public String currencyOrDefault(String fallback) {
        return currency == null || currency.isBlank()
                ? fallback
                : currency.trim().toUpperCase(Locale.ROOT);
    }

    /** Income is a real recurrence — a salary is the most regular one there is. */
    public String directionOrDefault() {
        return "credit".equalsIgnoreCase(direction) ? "credit" : "debit";
    }

    public boolean activeOrDefault() {
        return isActive == null || isActive;
    }

    /**
     * Whether this counts towards "what my subscriptions cost".
     *
     * <p>Defaults to true for a steady outgoing charge and false for anything
     * else: an electricity bill recurs but is not a plan anyone can cancel, and
     * a salary is not a cost at all.
     */
    public boolean subscriptionOrDefault(boolean detectedAsSteady) {
        return isSubscription == null ? detectedAsSteady : isSubscription;
    }
}
