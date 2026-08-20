package com.expensetracker.api.cards;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A credit card: what the bank says, and what the ledger actually shows.
 *
 * <p>The two are kept apart on purpose. {@code statementBalance} and
 * {@code minimumDue} are assertions the bank made on a particular date;
 * {@code outstanding} is computed from transactions and moves every time one is
 * added. Collapsing them would leave the user unable to tell whether a figure
 * came from their bank or from their own bookkeeping.
 *
 * @param outstanding       owed right now, derived from the ledger
 * @param available         limit minus outstanding; null without a limit
 * @param utilisation       percentage of the limit in use; null without a limit
 * @param currentSpend      spent since the last statement — the bill being built
 * @param paidSinceStatement payments made since the statement date
 * @param remainingDue      statement balance still unpaid
 * @param minimumRemaining  how much of the minimum payment is still outstanding
 * @param status            clear, tracking, due, minimum_met, paid or overdue
 */
public record CardView(
        UUID accountId,
        String name,
        String last4,
        String currency,
        boolean isArchived,

        BigDecimal creditLimit,
        BigDecimal outstanding,
        BigDecimal available,
        Double utilisation,

        Integer billingDay,
        Integer dueDay,
        LocalDate statementDate,
        LocalDate dueDate,
        LocalDate nextStatement,
        Long daysUntilDue,

        BigDecimal statementBalance,
        BigDecimal minimumDue,
        LocalDate lastStatementAt,

        BigDecimal currentSpend,
        BigDecimal paidSinceStatement,
        BigDecimal remainingDue,
        BigDecimal minimumRemaining,
        String status) {
}
