package com.expensetracker.api.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The guard that stands between a model's sentence and a figure the user's
 * records do not contain.
 *
 * <p>Worth stating plainly what is being defended against. A model told to
 * summarise spending will write "up around 15% on last month" when nothing it
 * was given says 15, because that is the shape a sentence about spending takes.
 * The number is not a lie the model tells; it is a number the sentence needed.
 * It would still be the only figure on that dashboard with no basis in the
 * user's records, and the reader has no way to tell it apart from the real ones.
 */
class SummaryNarratorTest {

    private static final List<String> FACTS = List.of(
            "Month: February 2025",
            "Total spent: ₹12,500",
            "Number of payments: 34",
            "Change in spending against last month: ₹2,300 more",
            "Category Food: ₹4,200 across 18 payments");

    @Nested
    @DisplayName("what the guard allows")
    class Allowed {

        @Test
        void a_sentence_quoting_the_figures_it_was_given() {
            assertThat(SummaryNarrator.numbersAreReal(
                    "You spent ₹12,500 across 34 payments, ₹2,300 more than last month.",
                    FACTS)).isTrue();
        }

        /** The same number, written the way a sentence writes it. */
        @Test
        void the_same_figure_punctuated_differently() {
            assertThat(SummaryNarrator.numbersAreReal("You spent 12500 this month.", FACTS))
                    .isTrue();
            assertThat(SummaryNarrator.numbersAreReal("You spent 12,500.00 this month.", FACTS))
                    .isTrue();
        }

        /**
         * Small whole numbers are how sentences are built rather than claims
         * about money, and rejecting them would throw away good writing far
         * more often than bad.
         */
        @Test
        void small_counts_that_hold_a_sentence_together() {
            assertThat(SummaryNarrator.numbersAreReal(
                    "Your 3 biggest categories account for most of it.", FACTS)).isTrue();
        }

        @Test
        void a_sentence_with_no_numbers_at_all() {
            assertThat(SummaryNarrator.numbersAreReal("A quiet month, all told.", FACTS))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("what the guard catches")
    class Rejected {

        /** The exact failure this exists for. */
        @Test
        void a_percentage_nobody_calculated() {
            assertThat(SummaryNarrator.numbersAreReal(
                    "You spent ₹12,500, up 15% on last month.", FACTS)).isFalse();
        }

        @Test
        void a_total_that_is_close_but_not_the_total() {
            assertThat(SummaryNarrator.numbersAreReal("You spent about ₹12,600.", FACTS))
                    .isFalse();
        }

        /**
         * Arithmetic is the thing the model is most specifically not allowed to
         * do. 12500 minus 4200 is 8300 and it is correct, and it still must not
         * appear: today it is a subtraction, tomorrow it is a projection, and
         * nothing about the sentence tells the reader which.
         */
        @Test
        void a_figure_the_model_worked_out_itself_even_when_correct() {
            assertThat(SummaryNarrator.numbersAreReal(
                    "Food was ₹4,200, leaving ₹8,300 across everything else.", FACTS))
                    .isFalse();
        }

        @Test
        void an_invented_count() {
            assertThat(SummaryNarrator.numbersAreReal(
                    "You made 47 payments this month.", FACTS)).isFalse();
        }

        /** A year is not exempt just because it looks structural. */
        @Test
        void a_number_above_the_structural_exemption() {
            assertThat(SummaryNarrator.numbersAreReal("Spending peaked in 2024.", FACTS))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the sentence written without a model")
    class Template {

        private final SummaryNarrator narrator = new SummaryNarrator(null);

        private Insights insights(int count, String expense, String previousExpense,
                String change, boolean partial, String projected) {
            Totals totals = Totals.of(BigDecimal.ZERO, new BigDecimal(expense), count);
            Totals previous = previousExpense == null
                    ? Totals.EMPTY
                    : Totals.of(BigDecimal.ZERO, new BigDecimal(previousExpense), 20);
            return new Insights("2025-02", "February 2025", "INR", partial, 10, 28, 10,
                    totals, previous, BigDecimal.ZERO,
                    change == null ? null : new BigDecimal(change),
                    projected == null ? null : new BigDecimal(projected),
                    BigDecimal.ZERO,
                    List.of(new CategorySlice(null, "Food", new BigDecimal("4200"),
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, 18)),
                    List.of(), List.of(), List.of(), false, true, "2024-01", "2025-02");
        }

        @Test
        void names_the_total_the_count_and_the_biggest_category() {
            String text = narrator.template(
                    insights(34, "12500", "10200", "2300", false, null));

            assertThat(text).contains("34 payments").contains("Food");
            assertThat(text).contains("12,500").contains("2,300").contains("more");
        }

        @Test
        void says_less_when_there_is_less_to_compare_against() {
            String text = narrator.template(insights(34, "12500", null, null, false, null));
            assertThat(text).contains("12,500").doesNotContain("than the same point");
        }

        @Test
        void offers_the_projection_only_mid_month() {
            assertThat(narrator.template(insights(34, "12500", null, null, true, "18000")))
                    .contains("18,000").contains("At this rate");
            assertThat(narrator.template(insights(34, "12500", null, null, false, "18000")))
                    .doesNotContain("At this rate");
        }

        /**
         * An empty month and a new account are different things and get
         * different sentences: one is a fact about February, the other is the
         * only instruction a new user needs.
         */
        @Test
        void distinguishes_a_quiet_month_from_a_new_account() {
            assertThat(narrator.template(insights(0, "0", null, null, false, null)))
                    .contains("February 2025").doesNotContain("Connect a mailbox");
        }

        /** Whatever else it says, it must never say "1 payments". */
        @Test
        void counts_one_payment_correctly() {
            assertThat(narrator.template(insights(1, "250", null, null, false, null)))
                    .contains("1 payment").doesNotContain("1 payments");
        }

        /**
         * The template is the default rather than a fallback, so it must be
         * expressible under the same guard the model's output faces. If it were
         * not, the two paths would disagree about what counts as a real figure
         * — and the disagreement would only ever show up as the template being
         * rejected on a page nobody was watching.
         */
        @Test
        void every_figure_it_quotes_is_one_the_model_would_have_been_given() {
            Insights month = insights(34, "12500", "10200", "2300", true, "18000");

            assertThat(SummaryNarrator.numbersAreReal(
                    narrator.template(month), narrator.facts(month))).isTrue();
        }
    }
}
