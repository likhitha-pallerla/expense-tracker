package com.expensetracker.api.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * These tests are the specification for what a person may type.
 *
 * <p>Every sentence here is one somebody would plausibly write while standing
 * at a counter. The point of the exercise is that a fixed date is passed in, so
 * "yesterday" is checked against an actual day rather than whatever the build
 * machine thinks the time is — a test that computes its expectation the same
 * way the code does would pass even if both were wrong.
 */
class NaturalEntryTest {

    /** A Wednesday, chosen so weekday arithmetic has somewhere to go wrong. */
    private static final LocalDate TODAY = LocalDate.of(2025, 2, 5);

    private static EntryDraft parse(String input) {
        return NaturalEntry.parse(input, TODAY);
    }

    @Nested
    @DisplayName("the sentence from the plan")
    class TheHeadlineCase {

        @Test
        void spent_850_on_dinner_at_zomato_using_hdfc_card() {
            EntryDraft draft = parse("Spent 850 on dinner at Zomato using HDFC card");

            assertThat(draft.isSuccess()).isTrue();
            assertThat(draft.amount()).isEqualByComparingTo("850");
            assertThat(draft.direction()).isEqualTo("debit");
            assertThat(draft.merchant()).isEqualToIgnoringCase("Zomato");
            assertThat(draft.description()).isEqualToIgnoringCase("dinner");
            assertThat(draft.accountHint()).containsIgnoringCase("HDFC");
            assertThat(draft.occurredOn()).isEqualTo(TODAY);
            assertThat(draft.dateExplicit()).isFalse();
            assertThat(draft.source()).isEqualTo(EntryDraft.SOURCE_RULES);
        }
    }

    @Nested
    @DisplayName("amounts")
    class Amount {

        @Test
        void a_bare_number() {
            assertThat(parse("500 groceries").amount()).isEqualByComparingTo("500");
        }

        @Test
        void with_a_currency_marker_in_any_form() {
            assertThat(parse("₹250 at starbucks").amount()).isEqualByComparingTo("250");
            assertThat(parse("Rs 1200 for rent").amount()).isEqualByComparingTo("1200");
            assertThat(parse("INR 99 spotify").amount()).isEqualByComparingTo("99");
            assertThat(parse("300 rupees for chai").amount()).isEqualByComparingTo("300");
        }

        @Test
        void with_paise() {
            assertThat(parse("spent 249.50 on lunch").amount()).isEqualByComparingTo("249.50");
        }

        /** Lakh grouping, which no locale-aware parser gets right by default. */
        @Test
        void grouped_the_indian_way() {
            assertThat(parse("paid 1,20,000 for the deposit").amount())
                    .isEqualByComparingTo("120000");
        }

        /**
         * The reason a currency marker wins outright rather than the first
         * number: both are plausible by shape and only the marker disambiguates.
         */
        @Test
        void a_marked_figure_beats_an_unmarked_one_wherever_it_sits() {
            assertThat(parse("2 coffees for rs 250").amount()).isEqualByComparingTo("250");
            assertThat(parse("3 tickets at 450 rupees").amount()).isEqualByComparingTo("450");
        }

        @Test
        void refuses_a_sentence_with_no_money_in_it() {
            EntryDraft draft = parse("lunch at zomato");
            assertThat(draft.isSuccess()).isFalse();
            assertThat(draft.problem()).contains("amount");
        }

        @Test
        void refuses_zero() {
            assertThat(parse("spent 0 on nothing").isSuccess()).isFalse();
        }

        @Test
        void a_weight_is_not_an_amount() {
            EntryDraft draft = parse("grabbed 250g of coffee beans");
            assertThat(draft.isSuccess()).isFalse();
            assertThat(draft.problem()).contains("amount");
        }

        @Test
        void a_distance_is_not_an_amount() {
            assertThat(parse("ran 5km this morning, cab back cost 300").amount())
                    .isEqualByComparingTo("300");
        }

        @Test
        void a_time_is_not_an_amount() {
            assertThat(parse("meeting at 3pm, paid 500 for the room").amount())
                    .isEqualByComparingTo("500");
        }

        @Test
        void a_full_stop_may_end_the_sentence() {
            assertThat(parse("spent 250.").amount()).isEqualByComparingTo("250");
        }

        @Test
        void but_a_full_stop_inside_a_figure_is_a_decimal_point() {
            assertThat(parse("spent 250.75 on lunch").amount()).isEqualByComparingTo("250.75");
        }
    }

    @Nested
    @DisplayName("direction")
    class Direction {

        @Test
        void spending_words_mean_money_left() {
            assertThat(parse("spent 200 on lunch").isCredit()).isFalse();
            assertThat(parse("paid 200 to raj").isCredit()).isFalse();
            assertThat(parse("bought 200 of petrol").isCredit()).isFalse();
        }

        @Test
        void receiving_words_mean_money_arrived() {
            assertThat(parse("received 5000 salary").isCredit()).isTrue();
            assertThat(parse("got 300 refund from amazon").isCredit()).isTrue();
            assertThat(parse("50 cashback").isCredit()).isTrue();
        }

        /**
         * The default is deliberately asymmetric. An expense filed as income
         * understates spending and flatters every chart on the dashboard, which
         * is the one direction a budgeting tool must not be wrong in.
         */
        @Test
        void defaults_to_spending_when_no_verb_says_otherwise() {
            assertThat(parse("250 lunch").isCredit()).isFalse();
            assertThat(parse("1200 at big bazaar").isCredit()).isFalse();
        }
    }

    @Nested
    @DisplayName("dates")
    class Dates {

        @Test
        void defaults_to_today_and_says_it_was_not_told() {
            EntryDraft draft = parse("250 lunch");
            assertThat(draft.occurredOn()).isEqualTo(TODAY);
            assertThat(draft.dateExplicit()).isFalse();
        }

        @Test
        void yesterday_and_the_day_before() {
            assertThat(parse("250 lunch yesterday").occurredOn())
                    .isEqualTo(LocalDate.of(2025, 2, 4));
            assertThat(parse("250 lunch day before yesterday").occurredOn())
                    .isEqualTo(LocalDate.of(2025, 2, 3));
        }

        @Test
        void counted_back_in_days() {
            assertThat(parse("spent 250 on lunch 3 days ago").occurredOn())
                    .isEqualTo(LocalDate.of(2025, 2, 2));
        }

        /** From Wednesday the 5th, last Friday is the 31st of January. */
        @Test
        void a_named_weekday_means_the_most_recent_one() {
            assertThat(parse("1500 uber last friday").occurredOn())
                    .isEqualTo(LocalDate.of(2025, 1, 31));
        }

        @Test
        void a_written_date() {
            assertThat(parse("paid 1200 on 12 Jan").occurredOn())
                    .isEqualTo(LocalDate.of(2025, 1, 12));
            assertThat(parse("paid 1200 on 12th January 2024").occurredOn())
                    .isEqualTo(LocalDate.of(2024, 1, 12));
        }

        @Test
        void a_numeric_date_is_read_day_first() {
            assertThat(parse("paid 1200 on 03/04").occurredOn())
                    .isEqualTo(LocalDate.of(2024, 4, 3));
        }

        /**
         * The whole reason dates are removed from the sentence before the
         * amount is looked for. Both 12 and 1200 are numbers; only the order of
         * the passes decides which one is the money.
         */
        @Test
        void a_date_is_never_mistaken_for_the_amount() {
            assertThat(parse("paid 1200 on 12 Jan").amount()).isEqualByComparingTo("1200");
            assertThat(parse("spent 450 on 3 days ago").amount()).isEqualByComparingTo("450");
        }

        /**
         * Nobody records a payment that has not happened yet, so an unqualified
         * date that lands in the future belongs to last year.
         */
        @Test
        void a_bare_date_in_the_future_is_read_as_last_year() {
            assertThat(parse("paid 1200 on 12 Dec").occurredOn())
                    .isEqualTo(LocalDate.of(2024, 12, 12));
        }

        /** An impossible date is dropped, not fatal — the amount still stands. */
        @Test
        void an_impossible_date_does_not_lose_the_payment() {
            EntryDraft draft = parse("paid 1200 on 31 Feb");
            assertThat(draft.isSuccess()).isTrue();
            assertThat(draft.amount()).isEqualByComparingTo("1200");
        }
    }

    @Nested
    @DisplayName("who and what")
    class Fields {

        @Test
        void merchant_after_at_or_to() {
            assertThat(parse("250 at starbucks").merchant()).isEqualToIgnoringCase("starbucks");
            assertThat(parse("paid 1200 to swiggy").merchant()).isEqualToIgnoringCase("swiggy");
        }

        @Test
        void merchant_stops_where_the_next_phrase_begins() {
            EntryDraft draft = parse("spent 300 at cafe coffee day with icici card");
            assertThat(draft.merchant()).isEqualToIgnoringCase("cafe coffee day");
            assertThat(draft.accountHint()).containsIgnoringCase("icici");
        }

        @Test
        void account_after_using_or_via() {
            assertThat(parse("250 lunch using hdfc card").accountHint())
                    .containsIgnoringCase("hdfc");
            assertThat(parse("250 lunch via paytm wallet").accountHint())
                    .containsIgnoringCase("paytm");
        }

        /**
         * The four digits are the only thing that tells two cards from the same
         * bank apart, so a phrase that carries them must keep them.
         */
        @Test
        void account_keeps_the_digits_that_identify_it() {
            assertThat(parse("1200 groceries using card 4821").accountHint())
                    .contains("4821");
            assertThat(parse("300 using hdfc card 4821").accountHint())
                    .containsIgnoringCase("hdfc").contains("4821");
            assertThat(parse("300 using card ending 4821").accountHint())
                    .contains("4821");
        }

        /** "using cash" names an account with no word in front of it. */
        @Test
        void account_named_by_the_keyword_alone() {
            assertThat(parse("paid 500 using cash").accountHint())
                    .isEqualToIgnoringCase("cash");
            assertThat(parse("paid 500 via upi").accountHint())
                    .isEqualToIgnoringCase("upi");
        }

        /** The digits must not be swallowed as the amount. */
        @Test
        void account_digits_are_not_the_payment() {
            assertThat(parse("1200 groceries using card 4821").amount())
                    .isEqualByComparingTo("1200");
        }

        /**
         * "500 groceries" names no merchant and no purpose explicitly, so what
         * is left of the sentence becomes the description. Filing it as
         * "Payment" would be strictly less useful than what the user typed.
         */
        @Test
        void leftover_words_become_the_description() {
            EntryDraft draft = parse("500 groceries");
            assertThat(draft.description()).isEqualToIgnoringCase("groceries");
        }

        @Test
        void a_multi_word_description() {
            assertThat(parse("spent 800 on weekly groceries").description())
                    .isEqualToIgnoringCase("weekly groceries");
        }
    }

    @Nested
    @DisplayName("how much was recognised")
    class Understanding {

        /**
         * Not a probability and not from a model — a count of recognised
         * fields, used only to decide how much of the confirmation form to
         * pre-fill.
         */
        @Test
        void counts_the_fields_that_were_found() {
            assertThat(parse("250").understood()).isEqualTo(1);
            assertThat(parse("Spent 850 on dinner at Zomato using HDFC card yesterday")
                    .understood()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        void copes_with_nothing() {
            assertThat(parse(null).isSuccess()).isFalse();
            assertThat(parse("").isSuccess()).isFalse();
            assertThat(parse("   ").isSuccess()).isFalse();
        }

        @Test
        void refuses_prose() {
            EntryDraft draft = parse("a".repeat(400));
            assertThat(draft.isSuccess()).isFalse();
            assertThat(draft.problem()).contains("too long");
        }

        @Test
        void survives_extra_whitespace() {
            EntryDraft draft = parse("  spent   850   on  dinner   at  Zomato  ");
            assertThat(draft.amount()).isEqualByComparingTo("850");
            assertThat(draft.merchant()).isEqualToIgnoringCase("Zomato");
            assertThat(draft.description()).isEqualToIgnoringCase("dinner");
        }

        @Test
        void is_not_confused_by_case() {
            assertThat(parse("SPENT 850 AT ZOMATO").amount()).isEqualByComparingTo("850");
            assertThat(parse("SPENT 850 AT ZOMATO").merchant()).isEqualToIgnoringCase("zomato");
        }

        /** Every draft says where it came from, so the UI can be honest. */
        @Test
        void always_reports_that_rules_read_it() {
            assertThat(parse("250 lunch").source()).isEqualTo(EntryDraft.SOURCE_RULES);
            assertThat(parse("nonsense").source()).isEqualTo(EntryDraft.SOURCE_RULES);
        }
    }
}
