package com.expensetracker.api.insights;

import java.math.BigDecimal;
import java.time.YearMonth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One month on the trend line.
 *
 * <p>Months with no activity are still present, holding zeroes. Dropping them
 * would pull the line straight across a gap and turn "I recorded nothing in
 * June" into "I spent evenly through June", which is a different and untrue
 * statement.
 *
 * @param partial true for a month still in progress, so the interface can avoid
 *                drawing it as though it were a finished total
 */
public record TrendPoint(
        String month,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal net,
        boolean partial) {

    public static TrendPoint of(YearMonth month, BigDecimal income, BigDecimal expense,
            boolean partial) {
        BigDecimal in = income == null ? BigDecimal.ZERO : income;
        BigDecimal out = expense == null ? BigDecimal.ZERO : expense;
        return new TrendPoint(month.toString(), in, out, in.subtract(out), partial);
    }

    /** A short label the interface can show without parsing the month itself. */
    @JsonProperty("label")
    public String label() {
        YearMonth parsed = YearMonth.parse(month);
        return switch (parsed.getMonthValue()) {
            case 1 -> "Jan";
            case 2 -> "Feb";
            case 3 -> "Mar";
            case 4 -> "Apr";
            case 5 -> "May";
            case 6 -> "Jun";
            case 7 -> "Jul";
            case 8 -> "Aug";
            case 9 -> "Sep";
            case 10 -> "Oct";
            case 11 -> "Nov";
            default -> "Dec";
        };
    }
}
