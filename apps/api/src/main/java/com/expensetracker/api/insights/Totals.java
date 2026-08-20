package com.expensetracker.api.insights;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Money in and out over one stretch of time.
 *
 * <p>{@code net} is stored rather than derived on the client so that every
 * surface agrees on what "left over" means: income minus expense, transfers
 * excluded, and nothing else.
 */
public record Totals(BigDecimal income, BigDecimal expense, BigDecimal net, int count) {

    public static final Totals EMPTY =
            new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);

    public static Totals of(BigDecimal income, BigDecimal expense, int count) {
        BigDecimal in = income == null ? BigDecimal.ZERO : income;
        BigDecimal out = expense == null ? BigDecimal.ZERO : expense;
        return new Totals(in, out, in.subtract(out), count);
    }

    /** True when nothing at all happened, which the interface says differently. */
    @JsonProperty("isEmpty")
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * The change from an earlier stretch, as a percentage.
     *
     * <p>Null when there is nothing to compare against. A jump from nothing to
     * something is not "up 100%" and not "up infinity"; it is a first, and
     * saying so is the interface's job, not arithmetic's.
     */
    public static BigDecimal percentChange(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0) {
            return null;
        }
        return now.subtract(before)
                .multiply(BigDecimal.valueOf(100))
                .divide(before.abs(), 1, RoundingMode.HALF_UP);
    }
}
