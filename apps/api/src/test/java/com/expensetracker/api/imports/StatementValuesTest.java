package com.expensetracker.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The amount and date readers. A wrong reading here is invisible — the number
 * still looks plausible in the ledger — so every real-world layout is pinned
 * down by a test.
 */
class StatementValuesTest {

    @Nested
    @DisplayName("amounts")
    class Amounts {

        @Test
        @DisplayName("reads Indian digit grouping")
        void indianGrouping() {
            assertThat(StatementValues.parseAmount("1,23,456.78"))
                    .contains(new BigDecimal("123456.78"));
        }

        @Test
        @DisplayName("reads western digit grouping")
        void westernGrouping() {
            assertThat(StatementValues.parseAmount("123,456.78"))
                    .contains(new BigDecimal("123456.78"));
        }

        @Test
        @DisplayName("drops a currency symbol")
        void currencySymbol() {
            assertThat(StatementValues.parseAmount("₹ 2,500.00")).contains(new BigDecimal("2500.00"));
            assertThat(StatementValues.parseAmount("INR 2500")).contains(new BigDecimal("2500"));
        }

        @Test
        @DisplayName("reads a Dr suffix as money out")
        void debitSuffix() {
            assertThat(StatementValues.parseAmount("2,500.00 Dr"))
                    .contains(new BigDecimal("-2500.00"));
            assertThat(StatementValues.parseAmount("2500 DEBIT")).contains(new BigDecimal("-2500"));
        }

        @Test
        @DisplayName("reads a Cr suffix as money in")
        void creditSuffix() {
            assertThat(StatementValues.parseAmount("2,500.00 Cr"))
                    .contains(new BigDecimal("2500.00"));
            assertThat(StatementValues.parseAmount("2500 CREDIT")).contains(new BigDecimal("2500"));
        }

        @Test
        @DisplayName("reads accounting parentheses as negative")
        void parentheses() {
            assertThat(StatementValues.parseAmount("(1,234.56)"))
                    .contains(new BigDecimal("-1234.56"));
        }

        @Test
        @DisplayName("reads a leading minus as negative")
        void leadingMinus() {
            assertThat(StatementValues.parseAmount("-500.25")).contains(new BigDecimal("-500.25"));
        }

        @Test
        @DisplayName("reads a leading plus as positive")
        void leadingPlus() {
            assertThat(StatementValues.parseAmount("+500.25")).contains(new BigDecimal("500.25"));
        }

        @Test
        @DisplayName("keeps the paise, so totals still reconcile")
        void keepsScale() {
            assertThat(StatementValues.parseAmount("100.50").get().scale()).isEqualTo(2);
        }

        @Test
        @DisplayName("treats blanks and dashes as no value")
        void blanks() {
            assertThat(StatementValues.parseAmount("")).isEmpty();
            assertThat(StatementValues.parseAmount("   ")).isEmpty();
            assertThat(StatementValues.parseAmount("-")).isEmpty();
            assertThat(StatementValues.parseAmount("\u2013")).isEmpty();
            assertThat(StatementValues.parseAmount(null)).isEmpty();
        }

        @Test
        @DisplayName("reads an unused column written as zero")
        void explicitZero() {
            assertThat(StatementValues.parseAmount("0.00")).contains(new BigDecimal("0.00"));
        }

        @Test
        @DisplayName("gives up on text rather than inventing a number")
        void nonNumeric() {
            assertThat(StatementValues.parseAmount("N/A")).isEmpty();
            assertThat(StatementValues.parseAmount("Opening Balance")).isEmpty();
        }
    }

    @Nested
    @DisplayName("dates")
    class Dates {

        @Test
        @DisplayName("reads ISO dates")
        void iso() {
            assertThat(StatementValues.parseDate("2025-04-03", true))
                    .contains(LocalDate.of(2025, 4, 3));
        }

        @Test
        @DisplayName("reads a day-first date")
        void dayFirst() {
            assertThat(StatementValues.parseDate("03/04/2025", true))
                    .contains(LocalDate.of(2025, 4, 3));
        }

        @Test
        @DisplayName("reads a month-first date")
        void monthFirst() {
            assertThat(StatementValues.parseDate("03/04/2025", false))
                    .contains(LocalDate.of(2025, 3, 4));
        }

        @Test
        @DisplayName("expands a two-digit year to this century")
        void twoDigitYear() {
            assertThat(StatementValues.parseDate("03/04/25", true))
                    .contains(LocalDate.of(2025, 4, 3));
        }

        @Test
        @DisplayName("reads a named month, whatever the separator")
        void namedMonth() {
            assertThat(StatementValues.parseDate("05-Jan-25", true))
                    .contains(LocalDate.of(2025, 1, 5));
            assertThat(StatementValues.parseDate("5 Jan 2025", true))
                    .contains(LocalDate.of(2025, 1, 5));
            assertThat(StatementValues.parseDate("Jan 5, 2025", true))
                    .contains(LocalDate.of(2025, 1, 5));
        }

        @Test
        @DisplayName("reads a named month even when the column is month-first")
        void namedMonthIgnoresOrder() {
            assertThat(StatementValues.parseDate("05-Jan-25", false))
                    .contains(LocalDate.of(2025, 1, 5));
        }

        @Test
        @DisplayName("reads dot and dash separators")
        void separators() {
            assertThat(StatementValues.parseDate("03-04-2025", true))
                    .contains(LocalDate.of(2025, 4, 3));
            assertThat(StatementValues.parseDate("03.04.2025", true))
                    .contains(LocalDate.of(2025, 4, 3));
        }

        @Test
        @DisplayName("reads a year-first numeric date without guessing")
        void yearFirst() {
            assertThat(StatementValues.parseDate("2025/04/03", false))
                    .contains(LocalDate.of(2025, 4, 3));
        }

        @Test
        @DisplayName("ignores a time appended to the date")
        void ignoresTime() {
            assertThat(StatementValues.parseDate("03/04/2025 14:32:11", true))
                    .contains(LocalDate.of(2025, 4, 3));
        }

        @Test
        @DisplayName("rejects an impossible date rather than rolling it over")
        void impossibleDate() {
            assertThat(StatementValues.parseDate("32/04/2025", true)).isEmpty();
            assertThat(StatementValues.parseDate("30/02/2025", true)).isEmpty();
        }

        @Test
        @DisplayName("gives up on text rather than inventing a date")
        void nonDate() {
            assertThat(StatementValues.parseDate("Opening Balance", true)).isEmpty();
            assertThat(StatementValues.parseDate("", true)).isEmpty();
            assertThat(StatementValues.parseDate(null, true)).isEmpty();
        }
    }

    @Nested
    @DisplayName("day-first inference")
    class DayFirstInference {

        @Test
        @DisplayName("a value above 12 in the first position settles it as day-first")
        void firstPartAboveTwelve() {
            assertThat(StatementValues.isDayFirst(List.of("03/04/2025", "17/04/2025"))).isTrue();
        }

        @Test
        @DisplayName("a value above 12 in the second position settles it as month-first")
        void secondPartAboveTwelve() {
            assertThat(StatementValues.isDayFirst(List.of("03/04/2025", "04/17/2025"))).isFalse();
        }

        @Test
        @DisplayName("falls back to day-first when the column is genuinely ambiguous")
        void ambiguous() {
            assertThat(StatementValues.isDayFirst(List.of("03/04/2025", "05/06/2025"))).isTrue();
        }

        @Test
        @DisplayName("ignores values it cannot read")
        void ignoresJunk() {
            assertThat(StatementValues.isDayFirst(List.of("Opening Balance", "17/04/2025"))).isTrue();
        }

        @Test
        @DisplayName("falls back to day-first with nothing to go on")
        void empty() {
            assertThat(StatementValues.isDayFirst(List.of())).isTrue();
        }
    }
}
