package com.expensetracker.api.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ForecastWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    @Nested
    class Sizing {

        @Test
        void coversTodayPlusTheRest() {
            ForecastWindow window = ForecastWindow.of(TODAY, 30);

            assertThat(window.days()).isEqualTo(30);
            assertThat(window.today()).isEqualTo(TODAY);
            assertThat(window.end()).isEqualTo(LocalDate.of(2026, 4, 8));
        }

        @Test
        void aSevenDayWindowEndsSixDaysLater() {
            assertThat(ForecastWindow.of(TODAY, 7).end())
                    .isEqualTo(LocalDate.of(2026, 3, 16));
        }

        @Test
        void tooShortIsRaisedRatherThanRefused() {
            assertThat(ForecastWindow.of(TODAY, 1).days()).isEqualTo(ForecastWindow.MIN_DAYS);
            assertThat(ForecastWindow.of(TODAY, 0).days()).isEqualTo(ForecastWindow.MIN_DAYS);
            assertThat(ForecastWindow.of(TODAY, -400).days()).isEqualTo(ForecastWindow.MIN_DAYS);
        }

        @Test
        void tooLongIsCappedRatherThanRefused() {
            assertThat(ForecastWindow.of(TODAY, 5000).days()).isEqualTo(ForecastWindow.MAX_DAYS);
        }

        @Test
        void theCapIsAboutADriftingCadenceNotPerformance() {
            // Three months. Past this a monthly series has drifted so far that
            // the arrival dates are fiction dressed up as precision.
            assertThat(ForecastWindow.MAX_DAYS).isEqualTo(92);
        }
    }

    @Nested
    class Membership {

        private final ForecastWindow window = ForecastWindow.of(TODAY, 30);

        @Test
        void todayIsInside() {
            assertThat(window.covers(TODAY)).isTrue();
        }

        @Test
        void theLastDayIsInside() {
            assertThat(window.covers(LocalDate.of(2026, 4, 8))).isTrue();
        }

        @Test
        void theDayAfterIsNot() {
            assertThat(window.covers(LocalDate.of(2026, 4, 9))).isFalse();
        }

        @Test
        void yesterdayIsNot() {
            assertThat(window.covers(TODAY.minusDays(1))).isFalse();
        }
    }

    @Nested
    class Indexing {

        private final ForecastWindow window = ForecastWindow.of(TODAY, 30);

        @Test
        void todayIsZero() {
            assertThat(window.indexOf(TODAY)).isZero();
        }

        @Test
        void tomorrowIsOne() {
            assertThat(window.indexOf(TODAY.plusDays(1))).isEqualTo(1);
        }

        @Test
        void theLastDayIsOneLessThanTheLength() {
            assertThat(window.indexOf(window.end())).isEqualTo(window.days() - 1);
        }

        @Test
        void crossesAMonthBoundaryWithoutTripping() {
            assertThat(window.indexOf(LocalDate.of(2026, 4, 1))).isEqualTo(22);
        }
    }
}
