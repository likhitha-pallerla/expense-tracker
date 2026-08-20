package com.expensetracker.api.health;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the score is computed from, gathered before any judgement is made.
 *
 * <p>Splitting measurement from scoring is what makes the score testable: every
 * field here is a plain number that came out of the ledger, and
 * {@link HealthScorer} never touches a database or a clock. It also keeps the
 * two kinds of mistake apart — a wrong query and a wrong opinion about what a
 * number means are found and fixed in different places.
 *
 * <p>A null is not a zero. {@code monthlyIncome} being null means no income was
 * ever recorded, which is a completely different situation from earning
 * nothing, and the scorer treats it that way.
 *
 * @param monthsObserved     complete calendar months of history in the window
 * @param transactionCount   rows inside the window, used as the floor below
 *                           which nothing is worth scoring
 * @param monthlyIncome      average income per month; null when none was recorded
 * @param monthlyExpense     average spend per month, transfers excluded
 * @param liquidBalance      cash across everything that is not a credit card
 * @param cardDebt           outstanding across all credit cards
 * @param creditLimit        total limit across cards that declare one; null when
 *                           no card does, which is not the same as a limit of nil
 * @param cardOutstanding    outstanding on those same cards, so utilisation
 *                           compares like with like
 * @param budgets            one entry per budget in a live window
 * @param monthlyCommitments confirmed recurring outflow expressed per month
 * @param commitmentCount    how many confirmed series that figure came from
 */
public record HealthFacts(
        int monthsObserved,
        LocalDate windowStart,
        LocalDate windowEnd,
        int transactionCount,
        BigDecimal monthlyIncome,
        BigDecimal monthlyExpense,
        BigDecimal liquidBalance,
        BigDecimal cardDebt,
        BigDecimal creditLimit,
        BigDecimal cardOutstanding,
        List<BudgetFact> budgets,
        BigDecimal monthlyCommitments,
        int commitmentCount,
        String currency) {

    /**
     * A budget reduced to the two things that matter here: how it is going, and
     * how much was riding on it.
     *
     * <p>The status is taken from the budgets module rather than recomputed, so
     * a budget shown as on track there can never be counted as blown here.
     */
    public record BudgetFact(String name, BigDecimal amount, String status) {
    }
}
