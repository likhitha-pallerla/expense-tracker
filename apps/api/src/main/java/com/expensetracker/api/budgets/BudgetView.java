package com.expensetracker.api.budgets;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A budget together with how it is actually going.
 *
 * <p>The figures are computed on read rather than stored, so editing or
 * deleting a transaction can never leave a budget showing a stale total.
 *
 * @param spent       spent inside the current window
 * @param carriedOver unspent allowance brought forward, when rollover is on
 * @param limit       {@code amount + carriedOver} — what may be spent now
 * @param remaining   {@code limit - spent}; negative when overspent
 * @param percentUsed 0–100+, rounded to one decimal
 * @param projected   what this pace would spend by the end of the window
 * @param status      {@code on_track}, {@code warning}, {@code over}, or {@code upcoming}
 */
public record BudgetView(
        UUID id,
        String name,
        UUID categoryId,
        String categoryName,
        BigDecimal amount,
        String currency,
        String period,
        LocalDate startsOn,
        LocalDate endsOn,
        boolean rollover,
        List<Integer> alertThresholds,
        boolean isActive,

        LocalDate periodStart,
        LocalDate periodEnd,
        long daysRemaining,
        long daysTotal,

        BigDecimal spent,
        BigDecimal carriedOver,
        BigDecimal limit,
        BigDecimal remaining,
        double percentUsed,
        BigDecimal projected,
        String status) {
}
