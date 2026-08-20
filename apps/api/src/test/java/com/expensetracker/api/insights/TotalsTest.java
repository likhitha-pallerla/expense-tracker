package com.expensetracker.api.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The numbers a dashboard reports")
class TotalsTest {

    @Nested
    @DisplayName("net")
    class Net {

        @Test
        @DisplayName("is what is left after spending")
        void leftOver() {
            Totals totals = Totals.of(new BigDecimal("50000"), new BigDecimal("32000"), 12);
            assertThat(totals.net()).isEqualByComparingTo("18000");
        }

        @Test
        @DisplayName("goes negative when more went out than came in")
        void overspent() {
            Totals totals = Totals.of(new BigDecimal("10000"), new BigDecimal("14500"), 9);
            assertThat(totals.net()).isEqualByComparingTo("-4500");
        }

        @Test
        @DisplayName("treats a missing sum as nothing, not as an error")
        void nulls() {
            Totals totals = Totals.of(null, null, 0);
            assertThat(totals.income()).isEqualByComparingTo("0");
            assertThat(totals.expense()).isEqualByComparingTo("0");
            assertThat(totals.net()).isEqualByComparingTo("0");
            assertThat(totals.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("is not empty just because it nets to zero")
        void balancedIsNotEmpty() {
            Totals totals = Totals.of(new BigDecimal("500"), new BigDecimal("500"), 2);
            assertThat(totals.net()).isEqualByComparingTo("0");
            assertThat(totals.isEmpty()).isFalse();
        }
    }

    @Nested
    @DisplayName("percentage change")
    class Change {

        @Test
        @DisplayName("reports a rise")
        void rise() {
            assertThat(Totals.percentChange(new BigDecimal("150"), new BigDecimal("100")))
                    .isEqualByComparingTo("50.0");
        }

        @Test
        @DisplayName("reports a fall as a negative")
        void fall() {
            assertThat(Totals.percentChange(new BigDecimal("75"), new BigDecimal("100")))
                    .isEqualByComparingTo("-25.0");
        }

        @Test
        @DisplayName("refuses to divide by nothing")
        void fromZero() {
            // Spending on something for the first time is not "up 100%" and not
            // "up infinity". It is a first, and only words can say that.
            assertThat(Totals.percentChange(new BigDecimal("900"), BigDecimal.ZERO)).isNull();
            assertThat(Totals.percentChange(new BigDecimal("900"), null)).isNull();
        }

        @Test
        @DisplayName("says nothing changed when nothing changed")
        void unchanged() {
            assertThat(Totals.percentChange(new BigDecimal("400"), new BigDecimal("400")))
                    .isEqualByComparingTo("0.0");
        }

        @Test
        @DisplayName("keeps one decimal place, so 1,003 against 1,000 is not 'no change'")
        void precision() {
            assertThat(Totals.percentChange(new BigDecimal("1003"), new BigDecimal("1000")))
                    .isEqualByComparingTo("0.3");
        }
    }

    @Nested
    @DisplayName("a point on the trend line")
    class Trend {

        @Test
        @DisplayName("carries a short label so the chart needs no date parsing")
        void label() {
            assertThat(TrendPoint.of(YearMonth.of(2026, 1), null, null, false).label())
                    .isEqualTo("Jan");
            assertThat(TrendPoint.of(YearMonth.of(2026, 12), null, null, false).label())
                    .isEqualTo("Dec");
        }

        @Test
        @DisplayName("is a real zero when a month had nothing in it")
        void emptyMonth() {
            TrendPoint point = TrendPoint.of(YearMonth.of(2026, 6), null, null, false);
            assertThat(point.income()).isEqualByComparingTo("0");
            assertThat(point.expense()).isEqualByComparingTo("0");
            assertThat(point.net()).isEqualByComparingTo("0");
            assertThat(point.month()).isEqualTo("2026-06");
        }

        @Test
        @DisplayName("nets income against expense")
        void nets() {
            TrendPoint point = TrendPoint.of(YearMonth.of(2026, 6),
                    new BigDecimal("60000"), new BigDecimal("41250.50"), true);
            assertThat(point.net()).isEqualByComparingTo("18749.50");
            assertThat(point.partial()).isTrue();
        }
    }
}
