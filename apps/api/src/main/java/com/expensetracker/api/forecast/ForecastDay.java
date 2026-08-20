package com.expensetracker.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One day of the projected balance, for drawing the line. */
public record ForecastDay(
        LocalDate date,
        BigDecimal balance,
        BigDecimal moneyIn,
        BigDecimal moneyOut,
        int events) {

    @JsonProperty("hasEvents")
    public boolean hasEvents() {
        return events > 0;
    }
}
