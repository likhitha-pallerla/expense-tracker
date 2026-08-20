package com.expensetracker.api.cards;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The parts of a credit card the bank tells you and the ledger cannot.
 *
 * <p>Live outstanding is deliberately absent: it is derived from transactions.
 * Everything here is an external fact — a limit the bank granted, the days it
 * bills on, and what the last statement said.
 *
 * @param statementBalance what the last statement said was owed
 * @param minimumDue       the minimum payment on that statement
 * @param lastStatementAt  the date of that statement
 */
public record CardRequest(
        @DecimalMin(value = "0.01", message = "A credit limit has to be more than zero")
        @Digits(integer = 12, fraction = 2, message = "Credit limit supports at most 2 decimal places")
        BigDecimal creditLimit,

        @Min(value = 1, message = "Billing day must be between 1 and 31")
        @Max(value = 31, message = "Billing day must be between 1 and 31")
        Integer billingDay,

        @Min(value = 1, message = "Due day must be between 1 and 31")
        @Max(value = 31, message = "Due day must be between 1 and 31")
        Integer dueDay,

        @DecimalMin(value = "0", message = "A statement balance cannot be negative")
        @Digits(integer = 12, fraction = 2, message = "Statement balance supports at most 2 decimal places")
        BigDecimal statementBalance,

        @DecimalMin(value = "0", message = "A minimum due cannot be negative")
        @Digits(integer = 12, fraction = 2, message = "Minimum due supports at most 2 decimal places")
        BigDecimal minimumDue,

        LocalDate lastStatementAt) {
}
