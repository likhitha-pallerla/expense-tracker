package com.expensetracker.api.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The month a dashboard is showing")
class InsightsWindowTest {

    @Nested
    @DisplayName("a month that is over")
    class Complete {

        private final InsightsWindow window =
                InsightsWindow.of(YearMonth.of(2026, 3), LocalDate.of(2026, 8, 14));

        @Test
        @DisplayName("covers the whole month")
        void wholeMonth() {
            assertThat(window.start()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(window.endExclusive()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(window.daysElapsed()).isEqualTo(31);
            assertThat(window.partial()).isFalse();
        }

        @Test
        @DisplayName("compares against the whole of the month before")
        void wholePreviousMonth() {
            assertThat(window.previousStart()).isEqualTo(LocalDate.of(2026, 2, 1));
            // February 2026 has 28 days, so 31 is clamped rather than running
            // into March and counting some days twice.
            assertThat(window.previousEndExclusive()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(window.previousDaysCounted()).isEqualTo(28);
        }

        @Test
        @DisplayName("is never projected, because it already happened")
        void noProjection() {
            assertThat(window.canProject()).isFalse();
        }
    }

    @Nested
    @DisplayName("a month still running")
    class Partial {

        private final InsightsWindow window =
                InsightsWindow.of(YearMonth.of(2026, 8), LocalDate.of(2026, 8, 12));

        @Test
        @DisplayName("counts only the days that have happened")
        void daysSoFar() {
            assertThat(window.daysElapsed()).isEqualTo(12);
            assertThat(window.daysInMonth()).isEqualTo(31);
            assertThat(window.partial()).isTrue();
        }

        @Test
        @DisplayName("compares against the same number of days last month")
        void likeForLike() {
            assertThat(window.previousStart()).isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(window.previousEndExclusive()).isEqualTo(LocalDate.of(2026, 7, 13));
            assertThat(window.previousDaysCounted()).isEqualTo(12);
        }

        @Test
        @DisplayName("still ends where the month ends, so late spending is not lost")
        void fullRange() {
            assertThat(window.endExclusive()).isEqualTo(LocalDate.of(2026, 9, 1));
        }

        @Test
        @DisplayName("can be projected once it is a few days in")
        void projects() {
            assertThat(window.canProject()).isTrue();
        }
    }

    @Nested
    @DisplayName("the first days of a month")
    class TooEarly {

        @Test
        @DisplayName("are not projected, because one payment would dominate")
        void refusesToGuess() {
            for (int day = 1; day <= 4; day++) {
                InsightsWindow window = InsightsWindow.of(YearMonth.of(2026, 8),
                        LocalDate.of(2026, 8, day));
                assertThat(window.canProject())
                        .as("day %d", day)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("start projecting on the fifth")
        void projectsFromDayFive() {
            assertThat(InsightsWindow.of(YearMonth.of(2026, 8), LocalDate.of(2026, 8, 5))
                    .canProject()).isTrue();
        }

        @Test
        @DisplayName("compare against one day when it is the first")
        void firstDay() {
            InsightsWindow window =
                    InsightsWindow.of(YearMonth.of(2026, 8), LocalDate.of(2026, 8, 1));
            assertThat(window.previousDaysCounted()).isEqualTo(1);
            assertThat(window.previousEndExclusive()).isEqualTo(LocalDate.of(2026, 7, 2));
        }
    }

    @Nested
    @DisplayName("edge cases in the calendar")
    class Calendar {

        @Test
        @DisplayName("the 31st compares against a whole shorter month, not into the next")
        void clampsToShortMonth() {
            InsightsWindow window =
                    InsightsWindow.of(YearMonth.of(2026, 3), LocalDate.of(2026, 3, 31));
            assertThat(window.daysElapsed()).isEqualTo(31);
            assertThat(window.previousEndExclusive()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(window.partial()).isFalse();
        }

        @Test
        @DisplayName("January reaches back into the previous year")
        void acrossNewYear() {
            InsightsWindow window =
                    InsightsWindow.of(YearMonth.of(2026, 1), LocalDate.of(2026, 1, 10));
            assertThat(window.previousStart()).isEqualTo(LocalDate.of(2025, 12, 1));
            assertThat(window.previousEndExclusive()).isEqualTo(LocalDate.of(2025, 12, 11));
        }

        @Test
        @DisplayName("a leap February is 29 days long")
        void leapYear() {
            InsightsWindow window =
                    InsightsWindow.of(YearMonth.of(2028, 2), LocalDate.of(2028, 3, 5));
            assertThat(window.daysInMonth()).isEqualTo(29);
            assertThat(window.daysElapsed()).isEqualTo(29);
        }

        @Test
        @DisplayName("a month yet to start holds nothing and promises nothing")
        void future() {
            InsightsWindow window =
                    InsightsWindow.of(YearMonth.of(2026, 12), LocalDate.of(2026, 8, 14));
            assertThat(window.daysElapsed()).isZero();
            assertThat(window.canProject()).isFalse();
            assertThat(window.previousStart()).isEqualTo(LocalDate.of(2026, 11, 1));
            // An empty comparison range rather than a backwards one.
            assertThat(window.previousEndExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
            assertThat(window.previousDaysCounted()).isZero();
        }

        @Test
        @DisplayName("the last day of a month is not treated as still running")
        void lastDay() {
            InsightsWindow window =
                    InsightsWindow.of(YearMonth.of(2026, 8), LocalDate.of(2026, 8, 31));
            assertThat(window.partial()).isFalse();
            assertThat(window.canProject()).isFalse();
        }
    }
}
