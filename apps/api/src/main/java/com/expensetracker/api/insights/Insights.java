package com.expensetracker.api.insights;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the dashboard shows, for one month, in one answer.
 *
 * <p>Deliberately a single response rather than four endpoints. Split across
 * separate calls, the totals and the category breakdown could be built from
 * different snapshots of the ledger and quietly disagree — and the first thing
 * anyone does with a dashboard is check whether the parts add up.
 *
 * @param currency        the user's base currency, which every figure is in
 * @param mixedCurrencies true when the month also contains transactions in
 *                        other currencies. They are still summed, because the
 *                        alternative is a total that silently omits money; the
 *                        flag exists so the interface can admit it
 * @param projectedExpense what the month looks likely to end at, or null when it
 *                        is too early to say anything honest
 * @param movers          the categories that changed most, up or down
 * @param hasHistory      whether this user has ever recorded anything, anywhere.
 *                        Deliberately not "is this month empty": someone
 *                        browsing back to a quiet month should see a quiet
 *                        month, not the first-run instructions
 * @param earliestMonth   the month their records start, so the interface knows
 *                        when to stop offering a step further back; null when
 *                        there are no records at all
 * @param currentMonth    the month it is right now where the user is. Sent
 *                        because the browser cannot be trusted to agree: the
 *                        interface needs it to know when to stop offering a
 *                        step forward, and its clock may be in another zone
 */
public record Insights(
        String month,
        String label,
        String currency,
        boolean partial,
        int daysElapsed,
        int daysInMonth,
        int previousDaysCounted,
        Totals totals,
        Totals previous,
        BigDecimal incomeChange,
        BigDecimal expenseChange,
        BigDecimal projectedExpense,
        BigDecimal uncategorisedAmount,
        List<CategorySlice> categories,
        List<CategorySlice> movers,
        List<MerchantSlice> merchants,
        List<TrendPoint> trend,
        boolean mixedCurrencies,
        boolean hasHistory,
        String earliestMonth,
        String currentMonth) {
}
