package com.expensetracker.api.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The gate between a model's opinion and somebody's transaction list.
 *
 * <p>Every test here is a thing a language model has actually done to a field
 * it was told to return a number in. None of them are hypothetical: models
 * return strings for numbers, currency symbols inside numbers, explanations
 * inside name fields, and dates from the wrong part of the message. The point
 * of this class is that all of those produce <em>no transaction</em> rather
 * than a wrong one.
 */
class AiAlertParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** 5 February 2025, 10:30 IST. */
    private static final Instant RECEIVED = Instant.parse("2025-02-05T05:00:00Z");

    private static JsonNode node(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    @DisplayName("amounts")
    class Amount {

        @Test
        void accepts_a_number() {
            assertThat(AiAlertParser.amountOf(node("1250"))).isEqualByComparingTo("1250");
            assertThat(AiAlertParser.amountOf(node("1250.75"))).isEqualByComparingTo("1250.75");
        }

        /** The same field, same model, different day. */
        @Test
        void accepts_the_string_forms_models_return_anyway() {
            assertThat(AiAlertParser.amountOf(node("\"1250\""))).isEqualByComparingTo("1250");
            assertThat(AiAlertParser.amountOf(node("\"1,250.00\""))).isEqualByComparingTo("1250.00");
            assertThat(AiAlertParser.amountOf(node("\"Rs. 1,250\""))).isEqualByComparingTo("1250");
            assertThat(AiAlertParser.amountOf(node("\"INR 1250\""))).isEqualByComparingTo("1250");
        }

        /** Lakh grouping has to mean here what it means everywhere else. */
        @Test
        void reads_indian_grouping_the_same_way_the_rules_do() {
            assertThat(AiAlertParser.amountOf(node("\"1,20,000\"")))
                    .isEqualByComparingTo("120000");
        }

        @Test
        void refuses_a_figure_that_is_not_one() {
            assertThat(AiAlertParser.amountOf(node("\"about 500\""))).isNull();
            assertThat(AiAlertParser.amountOf(node("\"500 or so\""))).isNull();
            assertThat(AiAlertParser.amountOf(node("\"unknown\""))).isNull();
            assertThat(AiAlertParser.amountOf(node("null"))).isNull();
            assertThat(AiAlertParser.amountOf(node("{}"))).isNull();
        }

        @Test
        void refuses_zero_and_negatives() {
            assertThat(AiAlertParser.amountOf(node("0"))).isNull();
            assertThat(AiAlertParser.amountOf(node("-500"))).isNull();
        }

        /** A hallucinated figure is usually an absurd one. */
        @Test
        void refuses_an_impossible_figure() {
            assertThat(AiAlertParser.amountOf(node("999999999999"))).isNull();
        }
    }

    @Nested
    @DisplayName("direction")
    class Direction {

        @Test
        void accepts_exactly_two_words() {
            assertThat(AiAlertParser.directionOf(node("\"debit\""))).isEqualTo("debit");
            assertThat(AiAlertParser.directionOf(node("\"credit\""))).isEqualTo("credit");
            assertThat(AiAlertParser.directionOf(node("\"DEBIT\""))).isEqualTo("debit");
        }

        /**
         * There is no safe default here. Without a direction the same figure is
         * either a payment or income, and guessing is a coin toss on somebody's
         * balance — so it is refused rather than defaulted.
         */
        @Test
        void refuses_everything_else() {
            assertThat(AiAlertParser.directionOf(node("\"outgoing\""))).isNull();
            assertThat(AiAlertParser.directionOf(node("\"spent\""))).isNull();
            assertThat(AiAlertParser.directionOf(node("\"withdrawal\""))).isNull();
            assertThat(AiAlertParser.directionOf(node("null"))).isNull();
            assertThat(AiAlertParser.directionOf(node("1"))).isNull();
        }
    }

    @Nested
    @DisplayName("dates")
    class Dates {

        @Test
        void accepts_the_day_the_alert_is_about() {
            assertThat(AiAlertParser.dateOf(node("\"2025-02-05\""), RECEIVED, IST))
                    .isEqualTo(Instant.parse("2025-02-04T18:30:00Z"));
        }

        @Test
        void accepts_an_alert_that_lagged_a_few_days() {
            assertThat(AiAlertParser.dateOf(node("\"2025-02-01\""), RECEIVED, IST)).isNotNull();
        }

        /**
         * Statements carry period ends, expiry dates and "customer since"
         * lines. A model that returns one of those has misread the message, and
         * the arrival time is a better answer than a confident wrong one.
         */
        @Test
        void refuses_a_date_from_a_different_part_of_the_message() {
            assertThat(AiAlertParser.dateOf(node("\"2028-11-30\""), RECEIVED, IST)).isNull();
            assertThat(AiAlertParser.dateOf(node("\"2019-06-01\""), RECEIVED, IST)).isNull();
        }

        @Test
        void refuses_a_date_after_the_alert_arrived() {
            assertThat(AiAlertParser.dateOf(node("\"2025-03-01\""), RECEIVED, IST)).isNull();
        }

        /** One day of slack, because timezones round the wrong way sometimes. */
        @Test
        void tolerates_a_timezone_rounding() {
            assertThat(AiAlertParser.dateOf(node("\"2025-02-06\""), RECEIVED, IST)).isNotNull();
        }

        @Test
        void refuses_a_non_date() {
            assertThat(AiAlertParser.dateOf(node("\"yesterday\""), RECEIVED, IST)).isNull();
            assertThat(AiAlertParser.dateOf(node("\"05/02/2025\""), RECEIVED, IST)).isNull();
            assertThat(AiAlertParser.dateOf(node("null"), RECEIVED, IST)).isNull();
        }
    }

    @Nested
    @DisplayName("merchant")
    class Merchant {

        @Test
        void keeps_a_name() {
            assertThat(AiAlertParser.merchantOf(node("\"SWIGGY\""))).isEqualTo("SWIGGY");
            assertThat(AiAlertParser.merchantOf(node("\"Cafe Coffee Day\"")))
                    .isEqualTo("Cafe Coffee Day");
        }

        /** The longest shape a real merchant name takes must still survive. */
        @Test
        void keeps_a_long_but_real_company_name() {
            assertThat(AiAlertParser.merchantOf(node("\"AMAZON PAY INDIA PRIVATE LIMITED\"")))
                    .isEqualTo("AMAZON PAY INDIA PRIVATE LIMITED");
        }

        /**
         * Models explain themselves in fields they were told to leave null.
         * Anything long enough to be a sentence is treated as one.
         */
        @Test
        void refuses_an_explanation() {
            assertThat(AiAlertParser.merchantOf(node(
                    "\"The message does not name a merchant, only an account number\"")))
                    .isNull();
        }

        @Test
        void refuses_the_word_null() {
            assertThat(AiAlertParser.merchantOf(node("\"null\""))).isNull();
            assertThat(AiAlertParser.merchantOf(node("\"\""))).isNull();
            assertThat(AiAlertParser.merchantOf(node("null"))).isNull();
        }
    }

    @Nested
    @DisplayName("last four digits")
    class Last4 {

        @Test
        void keeps_four_digits_however_they_are_written() {
            assertThat(AiAlertParser.last4Of(node("\"1234\""))).isEqualTo("1234");
            assertThat(AiAlertParser.last4Of(node("1234"))).isEqualTo("1234");
            assertThat(AiAlertParser.last4Of(node("\"XX1234\""))).isEqualTo("1234");
            assertThat(AiAlertParser.last4Of(node("\"**1234\""))).isEqualTo("1234");
        }

        /**
         * These digits pick which account a payment lands in. Three digits or
         * five means the model read something else, and an unassigned
         * transaction is visible and fixable where a misassigned one is not.
         */
        @Test
        void refuses_anything_that_is_not_exactly_four() {
            assertThat(AiAlertParser.last4Of(node("\"123\""))).isNull();
            assertThat(AiAlertParser.last4Of(node("\"12345\""))).isNull();
            assertThat(AiAlertParser.last4Of(node("\"unknown\""))).isNull();
            assertThat(AiAlertParser.last4Of(node("null"))).isNull();
        }
    }
}
