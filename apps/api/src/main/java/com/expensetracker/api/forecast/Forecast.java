package com.expensetracker.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the next few weeks look like.
 *
 * @param balanceToday    the sum of every open account right now
 * @param safeToSpend     what is left once every confirmed commitment in the
 *                        window has landed, floored at zero. Never negative,
 *                        because "you can safely spend minus four thousand" is
 *                        not advice
 * @param suspected       series we think are recurring but the user has not
 *                        confirmed. Listed so they are not a surprise, but kept
 *                        out of every total
 * @param unpredicted     what was actually spent per day, on average, beyond
 *                        the recurring series — the groceries and the coffees.
 *                        Not added to the projection, because a forecast that
 *                        includes a guess at discretionary spending cannot be
 *                        checked against reality by the person reading it
 * @param basedOn         how many confirmed series the projection rests on.
 *                        Zero means the flat line is an absence of data, not a
 *                        prediction of calm
 */
public record Forecast(
        LocalDate today,
        LocalDate end,
        int days,
        String currency,
        BigDecimal balanceToday,
        BigDecimal expectedIn,
        BigDecimal expectedOut,
        BigDecimal projectedBalance,
        BigDecimal safeToSpend,
        LowPoint low,
        List<ForecastDay> line,
        List<ExpectedCharge> upcoming,
        List<ExpectedCharge> suspected,
        BigDecimal unpredicted,
        int basedOn,
        boolean mixedCurrencies,
        boolean hasAccounts) {
}
