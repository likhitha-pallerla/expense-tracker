package com.expensetracker.api.insights;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A place money went, and how often.
 *
 * <p>The count matters as much as the total: 4,000 across one visit and 4,000
 * across forty are different habits, and only one of them is worth doing
 * something about.
 */
public record MerchantSlice(UUID merchantId, String name, BigDecimal amount, int count) {
}
