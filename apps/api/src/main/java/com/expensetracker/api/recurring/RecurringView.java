package com.expensetracker.api.recurring;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A recurring payment as the user sees it.
 *
 * <p>{@code id} is null while a series is only a suggestion: nothing has been
 * saved, so there is nothing to address. Confirming or dismissing one goes
 * through {@code matchKey} instead.
 *
 * @param state         suggested, confirmed or dismissed — what the user has
 *                      decided, as opposed to what the money is doing
 * @param status        what the money is doing: active, due, overdue, ended
 * @param typicalAmount the settled price
 * @param latestAmount  what it costs now
 * @param monthlyCost   every cadence expressed per month, so a yearly plan and
 *                      a weekly one can be added together honestly
 * @param reasons       why this was detected, for a user who wants to argue
 */
public record RecurringView(
        UUID id,
        String matchKey,
        String name,
        String state,
        String status,
        String direction,
        UUID categoryId,
        String categoryName,
        UUID accountId,
        String accountName,
        String currency,
        String cadence,
        int cadenceDays,
        BigDecimal typicalAmount,
        BigDecimal latestAmount,
        boolean amountVaries,
        boolean priceChanged,
        int occurrences,
        LocalDate firstCharge,
        LocalDate lastCharge,
        LocalDate nextExpected,
        Long daysUntilNext,
        BigDecimal monthlyCost,
        BigDecimal yearlyCost,
        boolean isSubscription,
        boolean isActive,
        double confidence,
        List<String> reasons,
        String notes) {
}
