package com.expensetracker.api.accounts;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create/update payload for an account.
 *
 * <p>{@code openingBalance} may be negative: a credit card starts life owing
 * money, and forcing it to zero would misreport net worth from day one.
 */
public record AccountRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 120, message = "Name must be 120 characters or fewer")
        String name,

        @NotBlank(message = "Type is required")
        String type,

        @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter code")
        String currency,

        @Pattern(regexp = "^[0-9]{4}$", message = "Last 4 must be exactly 4 digits")
        String last4,

        @DecimalMin(value = "-999999999999.99", message = "Opening balance is out of range")
        @DecimalMax(value = "999999999999.99", message = "Opening balance is out of range")
        @Digits(integer = 12, fraction = 2, message = "Opening balance supports at most 2 decimal places")
        BigDecimal openingBalance,

        Boolean isArchived,

        Integer sortOrder) {

    public String normalizedName() {
        return name == null ? null : name.trim();
    }

    /** Defaults to INR to match the profile default rather than failing the request. */
    public String currencyOrDefault() {
        return currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase();
    }

    public BigDecimal openingBalanceOrZero() {
        return openingBalance == null ? BigDecimal.ZERO : openingBalance;
    }
}
