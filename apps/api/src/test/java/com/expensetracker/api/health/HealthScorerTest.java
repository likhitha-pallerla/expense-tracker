package com.expensetracker.api.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.expensetracker.api.health.HealthFacts.BudgetFact;

class HealthScorerTest {

    private static final LocalDate FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate TO = LocalDate.of(2025, 3, 31);

    /** A user with enough history that nothing is rejected for being too new. */
    private static Facts base() {
        return new Facts();
    }

    /**
     * A mutable builder, so each test states only the numbers it cares about
     * and a reader can see the one thing being varied.
     */
    private static final class Facts {
        int months = 3;
        int transactions = 60;
        BigDecimal income = money(100_000);
        BigDecimal expense = money(80_000);
        BigDecimal liquid = money(240_000);
        BigDecimal cardDebt = BigDecimal.ZERO;
        BigDecimal creditLimit = money(200_000);
        BigDecimal cardOutstanding = money(20_000);
        List<BudgetFact> budgets = List.of(new BudgetFact("Food", money(10_000), "on_track"));
        BigDecimal commitments = money(20_000);
        int commitmentCount = 4;

        Facts months(int value) {
            months = value;
            return this;
        }

        Facts transactions(int value) {
            transactions = value;
            return this;
        }

        Facts income(BigDecimal value) {
            income = value;
            return this;
        }

        Facts expense(BigDecimal value) {
            expense = value;
            return this;
        }

        Facts liquid(BigDecimal value) {
            liquid = value;
            return this;
        }

        Facts cardDebt(BigDecimal value) {
            cardDebt = value;
            return this;
        }

        Facts credit(BigDecimal limit, BigDecimal outstanding) {
            creditLimit = limit;
            cardOutstanding = outstanding;
            return this;
        }

        Facts budgets(BudgetFact... values) {
            budgets = List.of(values);
            return this;
        }

        Facts commitments(BigDecimal value, int count) {
            commitments = value;
            commitmentCount = count;
            return this;
        }

        HealthReport score() {
            return HealthScorer.score(new HealthFacts(
                    months, FROM, TO, transactions, income, expense, liquid, cardDebt,
                    creditLimit, cardOutstanding, budgets, commitments, commitmentCount, "INR"));
        }

        HealthSignal signal(Driver driver) {
            return score().signals().stream()
                    .filter(s -> s.key().equals(driver.key()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    private static BigDecimal money(long value) {
        return BigDecimal.valueOf(value);
    }

    @Nested
    @DisplayName("too little data")
    class TooLittleData {

        @Test
        void refusesToScoreWithoutACompleteMonth() {
            HealthReport report = base().months(0).score();

            assertThat(report.score()).isNull();
            assertThat(report.grade()).isEqualTo("unrated");
            assertThat(report.missing()).anyMatch(m -> m.contains("complete calendar month"));
        }

        @Test
        void refusesToScoreATinyLedger() {
            HealthReport report = base().transactions(5).score();

            assertThat(report.score()).isNull();
            assertThat(report.missing()).anyMatch(m -> m.contains("Only 5 transactions"));
        }

        @Test
        void suggestsImportingAStatementToCatchUp() {
            assertThat(base().transactions(2).score().missing())
                    .anyMatch(m -> m.contains("bank statement"));
        }

        @Test
        void scoresOnceTheFloorIsCleared() {
            assertThat(base().months(1).transactions(8).score().score()).isNotNull();
        }

        @Test
        void producesNoSignalsAtAllRatherThanEmptyOnes() {
            assertThat(base().transactions(1).score().signals()).isEmpty();
        }
    }

    @Nested
    @DisplayName("savings rate")
    class SavingsRate {

        @Test
        void fullMarksForKeepingAThird() {
            HealthSignal signal = base().income(money(100_000)).expense(money(66_000))
                    .signal(Driver.SAVINGS_RATE);

            assertThat(signal.score()).isEqualTo(100);
            assertThat(signal.band()).isEqualTo("strong");
        }

        @Test
        void twentyPercentIsNearlyFullMarks() {
            assertThat(base().income(money(100_000)).expense(money(80_000))
                    .signal(Driver.SAVINGS_RATE).score()).isEqualTo(90);
        }

        @Test
        void breakingEvenIsPoorButNotZero() {
            assertThat(base().income(money(100_000)).expense(money(100_000))
                    .signal(Driver.SAVINGS_RATE).score()).isEqualTo(40);
        }

        @Test
        void spendingWellBeyondIncomeBottomsOut() {
            assertThat(base().income(money(100_000)).expense(money(140_000))
                    .signal(Driver.SAVINGS_RATE).score()).isZero();
        }

        @Test
        void saysHowMuchIsKept() {
            assertThat(base().income(money(100_000)).expense(money(80_000))
                    .signal(Driver.SAVINGS_RATE).finding())
                    .contains("20.0%")
                    .contains("20,000");
        }

        @Test
        void changesTheWordingWhenTheGapIsNegative() {
            HealthSignal signal = base().income(money(100_000)).expense(money(110_000))
                    .signal(Driver.SAVINGS_RATE);

            assertThat(signal.finding()).contains("more than you earn").contains("10,000");
            assertThat(signal.value()).isEqualTo(-10.0);
        }

        @Test
        void quantifiesTheGapToTwentyPercent() {
            assertThat(base().income(money(100_000)).expense(money(90_000))
                    .signal(Driver.SAVINGS_RATE).action())
                    .contains("10,000")
                    .contains("20%");
        }

        @Test
        void isNotMeasuredWithoutIncome() {
            HealthSignal signal = base().income(null).signal(Driver.SAVINGS_RATE);

            assertThat(signal.score()).isNull();
            assertThat(signal.weight()).isZero();
            assertThat(signal.band()).isEqualTo("unknown");
            assertThat(signal.action()).contains("salary");
        }
    }

    @Nested
    @DisplayName("cash buffer")
    class CashBuffer {

        @Test
        void sixMonthsOfCoverIsFullMarks() {
            assertThat(base().expense(money(50_000)).liquid(money(300_000))
                    .signal(Driver.CASH_BUFFER).score()).isEqualTo(100);
        }

        @Test
        void threeMonthsIsTheUsualFloor() {
            assertThat(base().expense(money(50_000)).liquid(money(150_000))
                    .signal(Driver.CASH_BUFFER).score()).isEqualTo(75);
        }

        @Test
        void noCashAtAllScoresZero() {
            assertThat(base().liquid(BigDecimal.ZERO).signal(Driver.CASH_BUFFER).score()).isZero();
        }

        @Test
        void cardDebtIsNettedOffTheCash() {
            HealthSignal withoutDebt = base().expense(money(50_000)).liquid(money(300_000))
                    .signal(Driver.CASH_BUFFER);
            HealthSignal withDebt = base().expense(money(50_000)).liquid(money(300_000))
                    .cardDebt(money(150_000)).signal(Driver.CASH_BUFFER);

            assertThat(withoutDebt.value()).isEqualTo(6.0);
            assertThat(withDebt.value()).isEqualTo(3.0);
        }

        @Test
        void debtBeyondCashIsSaidPlainly() {
            HealthSignal signal = base().expense(money(50_000)).liquid(money(20_000))
                    .cardDebt(money(70_000)).signal(Driver.CASH_BUFFER);

            assertThat(signal.score()).isZero();
            assertThat(signal.finding()).contains("exceeds your cash").contains("50,000");
        }

        @Test
        void aimsAtThreeMonthsFirstAndSixAfterwards() {
            assertThat(base().expense(money(50_000)).liquid(money(50_000))
                    .signal(Driver.CASH_BUFFER).action()).contains("3 months");
            assertThat(base().expense(money(50_000)).liquid(money(200_000))
                    .signal(Driver.CASH_BUFFER).action()).contains("6 months");
        }

        @Test
        void isNotMeasuredWithoutSpending() {
            assertThat(base().expense(BigDecimal.ZERO).signal(Driver.CASH_BUFFER).score()).isNull();
        }
    }

    @Nested
    @DisplayName("credit utilisation")
    class CreditUtilisation {

        @Test
        void anUntouchedCardIsNotAProblem() {
            HealthSignal signal = base().credit(money(200_000), BigDecimal.ZERO)
                    .signal(Driver.CREDIT_UTILISATION);

            assertThat(signal.score()).isEqualTo(100);
        }

        @Test
        void thirtyPercentIsTheLineLendersWatch() {
            assertThat(base().credit(money(200_000), money(60_000))
                    .signal(Driver.CREDIT_UTILISATION).score()).isEqualTo(75);
        }

        @Test
        void aMaxedCardScoresZero() {
            assertThat(base().credit(money(200_000), money(200_000))
                    .signal(Driver.CREDIT_UTILISATION).score()).isZero();
        }

        @Test
        void overTheLimitDoesNotGoNegative() {
            assertThat(base().credit(money(200_000), money(260_000))
                    .signal(Driver.CREDIT_UTILISATION).score()).isZero();
        }

        @Test
        void quantifiesThePaydownNeeded() {
            assertThat(base().credit(money(200_000), money(100_000))
                    .signal(Driver.CREDIT_UTILISATION).action())
                    .contains("40,000")
                    .contains("30%");
        }

        @Test
        void isNotMeasuredWithoutALimit() {
            HealthSignal signal = base().credit(null, BigDecimal.ZERO)
                    .signal(Driver.CREDIT_UTILISATION);

            assertThat(signal.score()).isNull();
            assertThat(signal.action()).contains("Set a limit");
        }
    }

    @Nested
    @DisplayName("budget discipline")
    class BudgetDiscipline {

        @Test
        void everythingOnTrackIsFullMarks() {
            assertThat(base()
                    .budgets(new BudgetFact("Food", money(10_000), "on_track"),
                            new BudgetFact("Travel", money(5_000), "on_track"))
                    .signal(Driver.BUDGET_DISCIPLINE).score()).isEqualTo(100);
        }

        @Test
        void aBlownBudgetCountsForItsSize() {
            // A small overspend alongside a large budget kept should not read
            // as half a failure.
            HealthSignal signal = base()
                    .budgets(new BudgetFact("Food", money(30_000), "on_track"),
                            new BudgetFact("Coffee", money(1_000), "over"))
                    .signal(Driver.BUDGET_DISCIPLINE);

            assertThat(signal.score()).isEqualTo(96);
        }

        @Test
        void aLargeOverspendDominates() {
            assertThat(base()
                    .budgets(new BudgetFact("Rent", money(30_000), "over"),
                            new BudgetFact("Coffee", money(1_000), "on_track"))
                    .signal(Driver.BUDGET_DISCIPLINE).score()).isEqualTo(3);
        }

        @Test
        void warningSitsBetweenTheTwo() {
            assertThat(base().budgets(new BudgetFact("Food", money(10_000), "warning"))
                    .signal(Driver.BUDGET_DISCIPLINE).score()).isEqualTo(60);
        }

        @Test
        void ignoresBudgetsThatHaveNotStartedOrHaveEnded() {
            HealthSignal signal = base()
                    .budgets(new BudgetFact("Food", money(10_000), "on_track"),
                            new BudgetFact("Old", money(90_000), "ended"),
                            new BudgetFact("Next year", money(90_000), "upcoming"))
                    .signal(Driver.BUDGET_DISCIPLINE);

            assertThat(signal.score()).isEqualTo(100);
            assertThat(signal.value()).isEqualTo(1.0);
        }

        @Test
        void namesTheBiggestOverspend() {
            assertThat(base()
                    .budgets(new BudgetFact("Coffee", money(1_000), "over"),
                            new BudgetFact("Groceries", money(20_000), "over"))
                    .signal(Driver.BUDGET_DISCIPLINE).action()).contains("Groceries");
        }

        @Test
        void countsHowManyAreHolding() {
            assertThat(base()
                    .budgets(new BudgetFact("A", money(1_000), "on_track"),
                            new BudgetFact("B", money(1_000), "over"))
                    .signal(Driver.BUDGET_DISCIPLINE).finding()).isEqualTo("1 of 2 budgets are on track.");
        }

        @Test
        void isNotMeasuredWithoutBudgets() {
            HealthSignal signal = base().budgets().signal(Driver.BUDGET_DISCIPLINE);

            assertThat(signal.score()).isNull();
            assertThat(signal.action()).contains("Set a budget");
        }

        @Test
        void doesNotAwardFullMarksForHavingNoBudgets() {
            HealthSignal signal = base().budgets().signal(Driver.BUDGET_DISCIPLINE);

            assertThat(signal.score()).isNotEqualTo(Integer.valueOf(100));
            assertThat(signal.weight()).isZero();
        }
    }

    @Nested
    @DisplayName("commitment load")
    class CommitmentLoad {

        @Test
        void underAThirdIsFullMarks() {
            assertThat(base().income(money(100_000)).commitments(money(25_000), 4)
                    .signal(Driver.COMMITMENT_LOAD).score()).isEqualTo(100);
        }

        @Test
        void halfOfIncomeIsAWarning() {
            assertThat(base().income(money(100_000)).commitments(money(50_000), 6)
                    .signal(Driver.COMMITMENT_LOAD).score()).isEqualTo(70);
        }

        @Test
        void everythingCommittedScoresZero() {
            assertThat(base().income(money(100_000)).commitments(money(100_000), 9)
                    .signal(Driver.COMMITMENT_LOAD).score()).isZero();
        }

        @Test
        void namesTheAmountAndTheCount() {
            assertThat(base().income(money(100_000)).commitments(money(40_000), 7)
                    .signal(Driver.COMMITMENT_LOAD).finding())
                    .contains("7 fixed commitments")
                    .contains("40%")
                    .contains("40,000");
        }

        @Test
        void isNotMeasuredUntilSomethingIsConfirmed() {
            HealthSignal signal = base().commitments(BigDecimal.ZERO, 0)
                    .signal(Driver.COMMITMENT_LOAD);

            assertThat(signal.score()).isNull();
            assertThat(signal.action()).contains("Recurring page");
        }

        @Test
        void isNotMeasuredWithoutIncomeToWeighItAgainst() {
            assertThat(base().income(null).signal(Driver.COMMITMENT_LOAD).score()).isNull();
        }
    }

    @Nested
    @DisplayName("weighting and coverage")
    class Weighting {

        @Test
        void reportsFullCoverageWhenEverythingIsMeasurable() {
            HealthReport report = base().score();

            assertThat(report.coverage()).isEqualTo(100);
            assertThat(report.headline()).doesNotContain("full picture");
        }

        @Test
        void dropsTheWeightOfWhatItCannotSee() {
            HealthReport report = base().credit(null, BigDecimal.ZERO).budgets().score();

            assertThat(report.coverage()).isEqualTo(70);
            assertThat(report.headline()).contains("70%");
        }

        @Test
        void renormalisesTheRemainingWeights() {
            HealthReport report = base().credit(null, BigDecimal.ZERO).budgets().score();

            int applied = report.signals().stream().mapToInt(HealthSignal::weight).sum();
            assertThat(applied).isEqualTo(100);
        }

        @Test
        void aMissingDriverDoesNotDragTheScoreDown() {
            // Everything measurable is perfect; the score must be perfect too,
            // even though two drivers could not be measured at all.
            HealthReport withAll = base().income(money(100_000)).expense(money(60_000))
                    .liquid(money(400_000)).credit(money(200_000), BigDecimal.ZERO)
                    .commitments(money(10_000), 3)
                    .budgets(new BudgetFact("Food", money(10_000), "on_track")).score();

            HealthReport withoutCards = base().income(money(100_000)).expense(money(60_000))
                    .liquid(money(400_000)).credit(null, BigDecimal.ZERO)
                    .commitments(money(10_000), 3)
                    .budgets(new BudgetFact("Food", money(10_000), "on_track")).score();

            assertThat(withAll.score()).isEqualTo(100);
            assertThat(withoutCards.score()).isEqualTo(100);
        }

        @Test
        void unratedWhenNothingCanBeMeasured() {
            HealthReport report = base().income(null).expense(BigDecimal.ZERO)
                    .credit(null, BigDecimal.ZERO).budgets().commitments(BigDecimal.ZERO, 0)
                    .score();

            assertThat(report.score()).isNull();
            assertThat(report.grade()).isEqualTo("unrated");
        }

        @Test
        void keepsEveryDriverInTheListEvenWhenUnscored() {
            HealthReport report = base().credit(null, BigDecimal.ZERO).budgets().score();

            assertThat(report.signals()).hasSize(5);
            assertThat(report.signals()).extracting(HealthSignal::key)
                    .containsExactly("savings_rate", "cash_buffer", "credit_utilisation",
                            "budget_discipline", "commitment_load");
        }
    }

    @Nested
    @DisplayName("grades, priorities and wins")
    class Narrative {

        @Test
        void gradesTheHealthyUserStrong() {
            HealthReport report = base().income(money(100_000)).expense(money(60_000))
                    .liquid(money(400_000)).credit(money(200_000), money(10_000))
                    .commitments(money(15_000), 3).score();

            assertThat(report.grade()).isEqualTo("strong");
            assertThat(report.score()).isGreaterThanOrEqualTo(80);
        }

        @Test
        void gradesTheStrugglingUserAtRisk() {
            HealthReport report = base().income(money(100_000)).expense(money(115_000))
                    .liquid(money(5_000)).cardDebt(money(80_000))
                    .credit(money(100_000), money(80_000))
                    .commitments(money(70_000), 8)
                    .budgets(new BudgetFact("Food", money(10_000), "over")).score();

            assertThat(report.grade()).isEqualTo("at_risk");
            assertThat(report.score()).isLessThan(35);
        }

        @Test
        void ordersPrioritiesByPointsRecoverableNotByLowestScore() {
            // Utilisation is the worse signal, but it is worth 15 points and
            // the savings rate is worth 30, so the savings advice comes first.
            HealthReport report = base().income(money(100_000)).expense(money(98_000))
                    .credit(money(100_000), money(90_000))
                    .liquid(money(400_000)).commitments(money(10_000), 2).score();

            assertThat(report.priorities()).isNotEmpty();
            assertThat(report.priorities().get(0)).contains("savings rate");
        }

        @Test
        void offersAtMostThreePriorities() {
            HealthReport report = base().income(money(100_000)).expense(money(120_000))
                    .liquid(BigDecimal.ZERO).credit(money(100_000), money(95_000))
                    .commitments(money(80_000), 9)
                    .budgets(new BudgetFact("Food", money(10_000), "over")).score();

            assertThat(report.priorities()).hasSizeLessThanOrEqualTo(3);
        }

        @Test
        void staysSilentAboutWhatIsAlreadyFine() {
            HealthReport report = base().income(money(100_000)).expense(money(60_000))
                    .liquid(money(400_000)).credit(money(200_000), BigDecimal.ZERO)
                    .commitments(money(10_000), 2).score();

            assertThat(report.priorities()).isEmpty();
        }

        @Test
        void listsWhatIsGoingWell() {
            HealthReport report = base().income(money(100_000)).expense(money(60_000))
                    .liquid(money(400_000)).credit(money(200_000), BigDecimal.ZERO)
                    .commitments(money(10_000), 2).score();

            assertThat(report.wins()).isNotEmpty();
        }

        @Test
        void collectsWhatIsMissingSeparatelyFromWhatIsWrong() {
            HealthReport report = base().credit(null, BigDecimal.ZERO).budgets().score();

            assertThat(report.missing()).hasSize(2);
            assertThat(report.missing()).anyMatch(m -> m.contains("Set a limit"));
            assertThat(report.missing()).anyMatch(m -> m.contains("Set a budget"));
        }

        @Test
        void carriesTheWindowThroughToTheReport() {
            HealthReport report = base().score();

            assertThat(report.windowStart()).isEqualTo(FROM);
            assertThat(report.windowEnd()).isEqualTo(TO);
            assertThat(report.monthsObserved()).isEqualTo(3);
            assertThat(report.currency()).isEqualTo("INR");
        }
    }

    @Nested
    @DisplayName("curve")
    class Curve {

        @Test
        void clampsBelowTheFirstPoint() {
            assertThat(HealthScorer.curve(-100, 0, 0, 10, 100)).isZero();
        }

        @Test
        void clampsAboveTheLastPoint() {
            assertThat(HealthScorer.curve(500, 0, 0, 10, 100)).isEqualTo(100);
        }

        @Test
        void interpolatesBetweenPoints() {
            assertThat(HealthScorer.curve(5, 0, 0, 10, 100)).isEqualTo(50);
        }

        @Test
        void handlesDescendingScores() {
            assertThat(HealthScorer.curve(20, 10, 100, 30, 0)).isEqualTo(50);
        }

        @Test
        void isContinuousAcrossSegmentBoundaries() {
            assertThat(HealthScorer.curve(10, 0, 0, 10, 60, 20, 100)).isEqualTo(60);
        }
    }

    @Nested
    @DisplayName("money formatting")
    class Money {

        @Test
        void dropsThePaise() {
            assertThat(HealthScorer.money(new BigDecimal("4213.67"), "INR")).doesNotContain(".");
        }

        @Test
        void groupsDigits() {
            assertThat(HealthScorer.money(new BigDecimal("120000"), "INR")).contains(",");
        }

        @Test
        void survivesAnUnknownCurrencyCode() {
            assertThat(HealthScorer.money(new BigDecimal("100"), "XYZ")).isNotBlank();
        }
    }
}
