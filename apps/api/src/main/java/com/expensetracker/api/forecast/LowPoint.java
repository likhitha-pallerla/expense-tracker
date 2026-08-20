package com.expensetracker.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The worst day between now and the end of the window.
 *
 * <p>The whole reason a forecast is worth having. A month-end balance can look
 * healthy while hiding a week where the rent, the card bill and the insurance
 * all land before payday — and a forecast that only reports the end of the
 * month would say everything is fine right up until the payment bounces.
 *
 * @param shortfall how much would be needed on that day to stay above zero,
 *                  or zero when the balance never goes negative
 */
public record LowPoint(
        LocalDate date,
        int daysAway,
        BigDecimal balance,
        BigDecimal shortfall) {

    @JsonProperty("goesNegative")
    public boolean goesNegative() {
        return balance.signum() < 0;
    }

    /** True when the low point is not simply today — i.e. worth pointing at. */
    @JsonProperty("isAhead")
    public boolean isAhead() {
        return daysAway > 0;
    }
}
