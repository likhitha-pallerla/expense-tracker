package com.expensetracker.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One payment we expect to happen, on one day.
 *
 * <p>A single recurring series produces several of these across a long window —
 * a weekly charge appears four or five times in a month — so this is an
 * occurrence, not a subscription.
 *
 * @param confirmed whether the user has agreed this series is real. A merely
 *                  suspected pattern still gets shown, but is kept out of the
 *                  committed total: telling someone they owe money they do not
 *                  is worse than being quiet about a guess
 * @param overdue   the charge was expected before today and has not arrived.
 *                  Rolled forward rather than dropped, because a late rent is
 *                  still rent, but flagged so the date is not trusted
 */
public record ExpectedCharge(
        UUID seriesId,
        String name,
        LocalDate expectedOn,
        int daysAway,
        BigDecimal amount,
        String direction,
        String currency,
        UUID categoryId,
        String categoryName,
        String cadence,
        boolean confirmed,
        boolean overdue,
        boolean amountVaries) {

    /** Signed the same way the ledger signs things: money out is negative. */
    public BigDecimal signedAmount() {
        return "credit".equals(direction) ? amount : amount.negate();
    }

    @JsonProperty("isIncome")
    public boolean isIncome() {
        return "credit".equals(direction);
    }
}
