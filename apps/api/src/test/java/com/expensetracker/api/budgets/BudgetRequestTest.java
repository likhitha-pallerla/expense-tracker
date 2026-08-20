package com.expensetracker.api.budgets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BudgetRequestTest {

    private static BudgetRequest request(String period, LocalDate startsOn, String currency,
            Boolean rollover, List<Integer> thresholds, Boolean active) {
        return new BudgetRequest("Food", UUID.randomUUID(), new BigDecimal("5000"),
                currency, period, startsOn, null, rollover, thresholds, active);
    }

    private static BudgetRequest bare() {
        return request(null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("period")
    class Period {

        @Test
        void defaultsToMonthly() {
            assertThat(bare().resolvedPeriod()).isEqualTo(BudgetPeriod.MONTHLY);
        }

        @Test
        void blankDefaultsToMonthly() {
            assertThat(request("   ", null, null, null, null, null).resolvedPeriod())
                    .isEqualTo(BudgetPeriod.MONTHLY);
        }

        @Test
        void readsAnyCasing() {
            assertThat(request("WEEKLY", null, null, null, null, null).resolvedPeriod())
                    .isEqualTo(BudgetPeriod.WEEKLY);
            assertThat(request(" yearly ", null, null, null, null, null).resolvedPeriod())
                    .isEqualTo(BudgetPeriod.YEARLY);
        }

        @Test
        void rejectsUnknownPeriod() {
            assertThatThrownBy(() -> request("daily", null, null, null, null, null).resolvedPeriod())
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("weekly, monthly or yearly");
        }
    }

    @Nested
    @DisplayName("start date")
    class Start {

        @Test
        void defaultsToToday() {
            LocalDate today = LocalDate.of(2025, 3, 14);
            assertThat(bare().resolvedStartsOn(today)).isEqualTo(today);
        }

        @Test
        void keepsAnExplicitDate() {
            LocalDate chosen = LocalDate.of(2025, 1, 25);
            assertThat(request(null, chosen, null, null, null, null)
                    .resolvedStartsOn(LocalDate.of(2025, 3, 14))).isEqualTo(chosen);
        }
    }

    @Nested
    @DisplayName("currency")
    class Currency {

        @Test
        void fallsBackWhenAbsent() {
            assertThat(bare().currencyOrDefault("INR")).isEqualTo("INR");
        }

        @Test
        void fallsBackWhenBlank() {
            assertThat(request(null, null, "  ", null, null, null).currencyOrDefault("USD"))
                    .isEqualTo("USD");
        }

        @Test
        void normalisesToUppercase() {
            assertThat(request(null, null, "inr", null, null, null).currencyOrDefault("USD"))
                    .isEqualTo("INR");
        }
    }

    @Nested
    @DisplayName("flags")
    class Flags {

        @Test
        void rolloverIsOffByDefault() {
            assertThat(bare().rolloverOrDefault()).isFalse();
        }

        @Test
        void rolloverHonoursTrue() {
            assertThat(request(null, null, null, true, null, null).rolloverOrDefault()).isTrue();
        }

        @Test
        void activeByDefault() {
            assertThat(bare().activeOrDefault()).isTrue();
        }

        @Test
        void canBeCreatedInactive() {
            assertThat(request(null, null, null, null, null, false).activeOrDefault()).isFalse();
        }
    }

    @Nested
    @DisplayName("alert thresholds")
    class Thresholds {

        @Test
        void defaultsWhenAbsent() {
            assertThat(bare().resolvedThresholds()).containsExactly(50, 80, 100);
        }

        @Test
        void defaultsWhenEmpty() {
            assertThat(request(null, null, null, null, List.of(), null).resolvedThresholds())
                    .containsExactly(50, 80, 100);
        }

        @Test
        void sortsAndDeduplicates() {
            assertThat(request(null, null, null, null, List.of(100, 25, 100, 75), null)
                    .resolvedThresholds()).containsExactly(25, 75, 100);
        }

        @Test
        void dropsZeroAndNegatives() {
            assertThat(request(null, null, null, null, List.of(0, -10, 60), null)
                    .resolvedThresholds()).containsExactly(60);
        }

        @Test
        void dropsValuesBeyondTheSmallintGuard() {
            assertThat(request(null, null, null, null, List.of(90, 900), null)
                    .resolvedThresholds()).containsExactly(90);
        }

        @Test
        void keepsFiveHundredAsTheBoundary() {
            assertThat(request(null, null, null, null, List.of(500), null)
                    .resolvedThresholds()).containsExactly(500);
        }

        @Test
        void ignoresNullEntries() {
            assertThat(request(null, null, null, null, Arrays.asList(80, null, 100), null)
                    .resolvedThresholds()).containsExactly(80, 100);
        }

        @Test
        void fallsBackWhenEverythingIsFilteredOut() {
            assertThat(request(null, null, null, null, List.of(-1, 0, 9999), null)
                    .resolvedThresholds()).containsExactly(50, 80, 100);
        }

        @Test
        void allowsOverspendWarnings() {
            assertThat(request(null, null, null, null, List.of(100, 120), null)
                    .resolvedThresholds()).containsExactly(100, 120);
        }
    }
}
