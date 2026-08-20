package com.expensetracker.api.cards;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Where a credit card is in its billing cycle.
 *
 * @param statementDate the most recent statement on or before today
 * @param dueDate       when that statement has to be paid
 * @param nextStatement when the next statement will be generated
 */
public record CardCycle(LocalDate statementDate, LocalDate dueDate, LocalDate nextStatement) {

    /**
     * Cards are billed on a day of the month, and short months do not have
     * every day. A card billed on the 31st bills on the 28th in February —
     * clamping, rather than spilling into March.
     */
    static LocalDate onDay(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    public static CardCycle of(int billingDay, int dueDay, LocalDate today) {
        YearMonth month = YearMonth.from(today);

        LocalDate candidate = onDay(month, billingDay);
        LocalDate statement = candidate.isAfter(today)
                ? onDay(month.minusMonths(1), billingDay)
                : candidate;

        // Always recomputed from the day number rather than added to the
        // clamped date, so February cannot strand every later month on the 28th.
        LocalDate next = onDay(YearMonth.from(statement).plusMonths(1), billingDay);

        return new CardCycle(statement, dueAfter(statement, dueDay), next);
    }

    /**
     * The first occurrence of the due day strictly after the statement.
     *
     * <p>Strictly, because a bill is never due the moment it is generated: a
     * card billed and due on the 10th gives the user until the 10th of the
     * following month.
     */
    private static LocalDate dueAfter(LocalDate statement, int dueDay) {
        YearMonth month = YearMonth.from(statement);
        LocalDate due = onDay(month, dueDay);
        return due.isAfter(statement) ? due : onDay(month.plusMonths(1), dueDay);
    }

    public long daysUntilDue(LocalDate today) {
        return ChronoUnit.DAYS.between(today, dueDate);
    }

    public boolean isOverdue(LocalDate today) {
        return today.isAfter(dueDate);
    }

    /** True while spending still lands on the statement being built. */
    public boolean isCurrentPeriod(LocalDate date) {
        return !date.isBefore(statementDate) && date.isBefore(nextStatement);
    }
}
