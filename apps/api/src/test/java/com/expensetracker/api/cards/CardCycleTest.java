package com.expensetracker.api.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CardCycleTest {

    private static LocalDate d(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    @Nested
    @DisplayName("statement date")
    class Statement {

        @Test
        void isThisMonthOnceTheBillingDayHasPassed() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 14));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 3, 5));
        }

        @Test
        void isLastMonthBeforeTheBillingDay() {
            CardCycle cycle = CardCycle.of(20, 10, d(2025, 3, 14));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 2, 20));
        }

        @Test
        void includesTheBillingDayItself() {
            CardCycle cycle = CardCycle.of(14, 2, d(2025, 3, 14));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 3, 14));
        }

        @Test
        void rollsBackAcrossAYearBoundary() {
            CardCycle cycle = CardCycle.of(20, 10, d(2025, 1, 5));
            assertThat(cycle.statementDate()).isEqualTo(d(2024, 12, 20));
        }
    }

    @Nested
    @DisplayName("short months")
    class ShortMonths {

        @Test
        void clampsTheThirtyFirstToTheEndOfFebruary() {
            CardCycle cycle = CardCycle.of(31, 20, d(2025, 2, 28));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 2, 28));
        }

        @Test
        void clampsToTheTwentyNinthInALeapYear() {
            CardCycle cycle = CardCycle.of(31, 20, d(2024, 2, 29));
            assertThat(cycle.statementDate()).isEqualTo(d(2024, 2, 29));
        }

        @Test
        void clampsThirtyDayMonths() {
            CardCycle cycle = CardCycle.of(31, 15, d(2025, 4, 30));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 4, 30));
        }

        /**
         * The whole point of recomputing from the day number: clamping to the
         * 28th in February must not drag March onto the 28th too.
         */
        @Test
        void doesNotStrandLaterMonthsAfterClamping() {
            CardCycle february = CardCycle.of(31, 20, d(2025, 2, 28));
            assertThat(february.nextStatement()).isEqualTo(d(2025, 3, 31));

            CardCycle march = CardCycle.of(31, 20, d(2025, 3, 31));
            assertThat(march.statementDate()).isEqualTo(d(2025, 3, 31));
        }
    }

    @Nested
    @DisplayName("due date")
    class Due {

        @Test
        void fallsInTheSameMonthWhenItIsAfterBilling() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 14));
            assertThat(cycle.dueDate()).isEqualTo(d(2025, 3, 25));
        }

        @Test
        void rollsIntoTheNextMonthWhenItIsBeforeBilling() {
            CardCycle cycle = CardCycle.of(25, 15, d(2025, 3, 28));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 3, 25));
            assertThat(cycle.dueDate()).isEqualTo(d(2025, 4, 15));
        }

        /** A bill is never due the instant it is generated. */
        @Test
        void givesAFullMonthWhenBillingAndDueDaysMatch() {
            CardCycle cycle = CardCycle.of(10, 10, d(2025, 3, 14));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 3, 10));
            assertThat(cycle.dueDate()).isEqualTo(d(2025, 4, 10));
        }

        @Test
        void clampsADueDayThatTheMonthDoesNotHave() {
            CardCycle cycle = CardCycle.of(1, 31, d(2025, 2, 10));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 2, 1));
            assertThat(cycle.dueDate()).isEqualTo(d(2025, 2, 28));
        }

        /** Clamping can make the due day land on the statement; it must move on. */
        @Test
        void movesOnWhenClampingCollidesWithTheStatement() {
            CardCycle cycle = CardCycle.of(31, 30, d(2025, 2, 28));
            assertThat(cycle.statementDate()).isEqualTo(d(2025, 2, 28));
            assertThat(cycle.dueDate()).isEqualTo(d(2025, 3, 30));
        }

        @Test
        void crossesTheYearBoundary() {
            CardCycle cycle = CardCycle.of(25, 15, d(2024, 12, 30));
            assertThat(cycle.dueDate()).isEqualTo(d(2025, 1, 15));
        }
    }

    @Nested
    @DisplayName("next statement")
    class Next {

        @Test
        void isOneMonthOn() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 14));
            assertThat(cycle.nextStatement()).isEqualTo(d(2025, 4, 5));
        }

        @Test
        void crossesTheYearBoundary() {
            CardCycle cycle = CardCycle.of(20, 10, d(2024, 12, 25));
            assertThat(cycle.nextStatement()).isEqualTo(d(2025, 1, 20));
        }

        @Test
        void isAlwaysAfterTheStatement() {
            CardCycle cycle = CardCycle.of(31, 20, d(2025, 2, 28));
            assertThat(cycle.nextStatement()).isAfter(cycle.statementDate());
        }
    }

    @Nested
    @DisplayName("countdown")
    class Countdown {

        @Test
        void countsDaysUntilDue() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 14));
            assertThat(cycle.daysUntilDue(d(2025, 3, 14))).isEqualTo(11);
        }

        @Test
        void isZeroOnTheDueDate() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 25));
            assertThat(cycle.daysUntilDue(d(2025, 3, 25))).isZero();
        }

        @Test
        void goesNegativeAfterTheDueDate() {
            CardCycle cycle = CardCycle.of(5, 10, d(2025, 3, 14));
            assertThat(cycle.daysUntilDue(d(2025, 3, 14))).isNegative();
        }

        @Test
        void isNotOverdueOnTheDueDateItself() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 25));
            assertThat(cycle.isOverdue(d(2025, 3, 25))).isFalse();
        }

        @Test
        void isOverdueTheDayAfter() {
            CardCycle cycle = CardCycle.of(5, 10, d(2025, 3, 11));
            assertThat(cycle.isOverdue(d(2025, 3, 11))).isTrue();
        }
    }

    @Nested
    @DisplayName("current period")
    class CurrentPeriod {

        @Test
        void includesTheStatementDay() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 14));
            assertThat(cycle.isCurrentPeriod(d(2025, 3, 5))).isTrue();
        }

        @Test
        void excludesTheNextStatementDay() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 14));
            assertThat(cycle.isCurrentPeriod(d(2025, 4, 5))).isFalse();
        }

        @Test
        void excludesTheDayBefore() {
            CardCycle cycle = CardCycle.of(5, 25, d(2025, 3, 14));
            assertThat(cycle.isCurrentPeriod(d(2025, 3, 4))).isFalse();
        }
    }
}
