package com.expensetracker.api.budgets;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Budget windows decide which spending counts, so an off-by-one here shows the
 * user a wrong number they have no way to check.
 */
class BudgetWindowTest {

    private static BudgetWindow monthly(String startsOn, String today) {
        return BudgetWindow.current(BudgetPeriod.MONTHLY,
                LocalDate.parse(startsOn), LocalDate.parse(today));
    }

    @Nested
    @DisplayName("monthly")
    class Monthly {

        @Test
        @DisplayName("runs from the budget's own start day, not the 1st")
        void followsStartDay() {
            BudgetWindow window = monthly("2025-01-25", "2025-03-02");

            assertThat(window.start()).isEqualTo(LocalDate.parse("2025-02-25"));
            assertThat(window.endExclusive()).isEqualTo(LocalDate.parse("2025-03-25"));
            assertThat(window.endInclusive()).isEqualTo(LocalDate.parse("2025-03-24"));
        }

        @Test
        @DisplayName("the start date itself falls in the first window")
        void startDayIncluded() {
            BudgetWindow window = monthly("2025-01-25", "2025-01-25");

            assertThat(window.index()).isZero();
            assertThat(window.contains(LocalDate.parse("2025-01-25"))).isTrue();
        }

        @Test
        @DisplayName("the day before the next window still belongs to this one")
        void lastDayIncluded() {
            BudgetWindow window = monthly("2025-01-25", "2025-02-24");

            assertThat(window.index()).isZero();
            assertThat(window.endExclusive()).isEqualTo(LocalDate.parse("2025-02-25"));
        }

        @Test
        @DisplayName("the first day of the next window rolls over")
        void rollsOver() {
            BudgetWindow window = monthly("2025-01-25", "2025-02-25");

            assertThat(window.index()).isEqualTo(1);
            assertThat(window.start()).isEqualTo(LocalDate.parse("2025-02-25"));
        }

        @Test
        @DisplayName("a budget starting on the 31st does not drift to the 28th")
        void doesNotDrift() {
            // February has no 31st. Stepping month by month would clamp to the
            // 28th and never recover; measuring from the start date does.
            assertThat(monthly("2025-01-31", "2025-02-15").start())
                    .isEqualTo(LocalDate.parse("2025-01-31"));
            assertThat(monthly("2025-01-31", "2025-03-01").start())
                    .isEqualTo(LocalDate.parse("2025-02-28"));
            assertThat(monthly("2025-01-31", "2025-03-31").start())
                    .isEqualTo(LocalDate.parse("2025-03-31"));
            assertThat(monthly("2025-01-31", "2025-05-31").start())
                    .isEqualTo(LocalDate.parse("2025-05-31"));
        }

        @Test
        @DisplayName("handles the 29th of February in a leap year")
        void leapYear() {
            assertThat(monthly("2024-01-29", "2024-02-29").start())
                    .isEqualTo(LocalDate.parse("2024-02-29"));
            assertThat(monthly("2024-02-29", "2025-02-28").start())
                    .isEqualTo(LocalDate.parse("2025-02-28"));
        }

        @Test
        @DisplayName("a budget that has not started yet shows its first window")
        void notStartedYet() {
            BudgetWindow window = monthly("2025-06-01", "2025-05-20");

            assertThat(window.index()).isZero();
            assertThat(window.start()).isEqualTo(LocalDate.parse("2025-06-01"));
        }

        @Test
        @DisplayName("stays correct years later")
        void farFuture() {
            BudgetWindow window = monthly("2020-03-15", "2025-08-20");

            assertThat(window.start()).isEqualTo(LocalDate.parse("2025-08-15"));
            assertThat(window.endExclusive()).isEqualTo(LocalDate.parse("2025-09-15"));
            assertThat(window.index()).isEqualTo(65);
        }
    }

    @Nested
    @DisplayName("weekly and yearly")
    class OtherPeriods {

        @Test
        @DisplayName("a weekly budget runs seven days from its start weekday")
        void weekly() {
            BudgetWindow window = BudgetWindow.current(BudgetPeriod.WEEKLY,
                    LocalDate.parse("2025-04-07"), LocalDate.parse("2025-04-20"));

            assertThat(window.start()).isEqualTo(LocalDate.parse("2025-04-14"));
            assertThat(window.endExclusive()).isEqualTo(LocalDate.parse("2025-04-21"));
            assertThat(window.totalDays()).isEqualTo(7);
        }

        @Test
        @DisplayName("a yearly budget runs from its anniversary")
        void yearly() {
            BudgetWindow window = BudgetWindow.current(BudgetPeriod.YEARLY,
                    LocalDate.parse("2023-04-01"), LocalDate.parse("2025-08-20"));

            assertThat(window.start()).isEqualTo(LocalDate.parse("2025-04-01"));
            assertThat(window.endExclusive()).isEqualTo(LocalDate.parse("2026-04-01"));
        }
    }

    @Nested
    @DisplayName("days remaining")
    class DaysRemaining {

        @Test
        @DisplayName("counts today as still available")
        void includesToday() {
            BudgetWindow window = monthly("2025-04-01", "2025-04-30");

            assertThat(window.daysRemaining(LocalDate.parse("2025-04-30"))).isEqualTo(1);
        }

        @Test
        @DisplayName("is zero once the window has passed")
        void neverNegative() {
            BudgetWindow window = BudgetWindow.at(BudgetPeriod.MONTHLY,
                    LocalDate.parse("2025-01-01"), 0);

            assertThat(window.daysRemaining(LocalDate.parse("2025-06-01"))).isZero();
        }

        @Test
        @DisplayName("a budget starting later shows its whole first window")
        void beforeStart() {
            BudgetWindow window = monthly("2025-06-01", "2025-05-20");

            assertThat(window.daysRemaining(LocalDate.parse("2025-05-20"))).isEqualTo(30);
        }
    }

    @Nested
    @DisplayName("period parsing")
    class Parsing {

        @Test
        @DisplayName("defaults to monthly")
        void defaultsToMonthly() {
            assertThat(BudgetPeriod.from(null)).isEqualTo(BudgetPeriod.MONTHLY);
            assertThat(BudgetPeriod.from("  ")).isEqualTo(BudgetPeriod.MONTHLY);
        }

        @Test
        @DisplayName("accepts the database's own spelling")
        void acceptsDbValues() {
            assertThat(BudgetPeriod.from("weekly")).isEqualTo(BudgetPeriod.WEEKLY);
            assertThat(BudgetPeriod.from("YEARLY")).isEqualTo(BudgetPeriod.YEARLY);
            assertThat(BudgetPeriod.MONTHLY.dbValue()).isEqualTo("monthly");
        }

        @Test
        @DisplayName("rejects anything else rather than silently defaulting")
        void rejectsUnknown() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> BudgetPeriod.from("fortnightly"))
                    .hasMessageContaining("weekly, monthly or yearly");
        }
    }
}
