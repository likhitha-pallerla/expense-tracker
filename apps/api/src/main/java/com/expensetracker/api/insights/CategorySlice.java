package com.expensetracker.api.insights;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What one category cost this month, and what it cost over the same stretch of
 * the month before.
 *
 * <p>Both figures travel together because a category total on its own says
 * almost nothing. "Groceries 8,400" is a fact; "Groceries 8,400, up from 5,100"
 * is a reason to look.
 *
 * @param categoryId null for spending that has not been categorised, which is
 *                   deliberately shown as its own row rather than hidden
 * @param share      percentage of this month's spending, 0–100
 */
public record CategorySlice(
        UUID categoryId,
        String name,
        BigDecimal amount,
        BigDecimal previousAmount,
        BigDecimal delta,
        BigDecimal percentChange,
        BigDecimal share,
        int count) {

    /** Spending with no category attached; the first thing worth fixing. */
    @JsonProperty("isUncategorised")
    public boolean isUncategorised() {
        return categoryId == null;
    }
}
