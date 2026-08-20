package com.expensetracker.api.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundedTest {

    /**
     * A pattern whose work grows with the square of the input.
     *
     * <p>Not the textbook {@code (a+)+b}: Java 21 optimises that one down to
     * polynomial time, so it no longer demonstrates anything. This still grows
     * quadratically, which against a mail body of any real size is minutes of a
     * core — and mail bodies are as long as the sender wants them to be.
     */
    private static final Pattern QUADRATIC = Pattern.compile("^(a+a+)+b$");

    /**
     * Shorter than plenty of real bank mail, which arrives wrapped in HTML.
     * Roughly 8n² character reads, so this is comfortably over the budget while
     * still being an entirely plausible message.
     */
    private static final String BAIT = "a".repeat(4_000) + "!";

    @Test
    @DisplayName("reads the same characters as the string it wraps")
    void transparent() {
        Bounded bounded = new Bounded("hello");
        assertThat(bounded.length()).isEqualTo(5);
        assertThat(bounded.charAt(0)).isEqualTo('h');
        assertThat(bounded).hasToString("hello");
    }

    @Test
    @DisplayName("a subsequence still reads correctly")
    void subSequence() {
        assertThat(new Bounded("hello world").subSequence(6, 11)).hasToString("world");
    }

    @Test
    @DisplayName("an ordinary match is nowhere near the budget")
    void ordinaryMatchIsUnaffected() {
        String alert = "Rs.450.00 debited from A/c XX1234 on 12-08-25 to VPA shop@upi.";
        Bounded bounded = new Bounded(alert);
        assertThat(Pattern.compile("(?i)rs\\.?\\s*([0-9.]+)").matcher(bounded).find()).isTrue();
    }

    @Test
    @DisplayName("a pattern that would run for minutes is stopped instead")
    void catastrophicBacktrackingIsStopped() {
        // Without the guard this runs for minutes on a message a stranger sent.
        assertThatThrownBy(() -> QUADRATIC.matcher(new Bounded(BAIT)).find())
                .isInstanceOf(Bounded.BudgetExceeded.class);
    }

    @Test
    @DisplayName("and it is stopped quickly, not eventually")
    void stoppedQuickly() {
        long started = System.nanoTime();
        try {
            QUADRATIC.matcher(new Bounded(BAIT)).find();
        } catch (Bounded.BudgetExceeded expected) {
            // The point of the test is the clock, not the exception.
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("an extractor treats an exhausted budget as no match")
    void extractorSurvivesIt() {
        Extractor extractor = new Extractor(QUADRATIC, 1, Extractor.Kind.TEXT);
        assertThat(extractor.capture(new Bounded(BAIT))).isNull();
    }

    @Test
    @DisplayName("a rule with a runaway match pattern simply does not apply")
    void ruleSurvivesIt() {
        ParserRule rule = new ParserRule(null, null, "runaway", null, QUADRATIC,
                java.util.Map.of(), 1);
        assertThat(rule.appliesTo(null, new Bounded(BAIT))).isFalse();
    }

    @Test
    @DisplayName("the budget is shared, so slicing cannot escape it")
    void subSequenceSharesTheBudget() {
        Bounded bounded = new Bounded(BAIT);
        assertThatThrownBy(() -> QUADRATIC.matcher(bounded.subSequence(0, BAIT.length())).find())
                .isInstanceOf(Bounded.BudgetExceeded.class);
        // The parent is now exhausted too, which is the point.
        assertThatThrownBy(() -> bounded.charAt(0))
                .isInstanceOf(Bounded.BudgetExceeded.class);
    }
}
