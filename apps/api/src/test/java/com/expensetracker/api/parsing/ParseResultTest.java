package com.expensetracker.api.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParseResultTest {

    @Test
    @DisplayName("nothing to read says so rather than showing zeroes")
    void nothingToRead() {
        assertThat(new ParseResult(0, 0, 0, 0, 0).summary()).isEqualTo("No new alerts to read.");
    }

    @Test
    @DisplayName("one transaction is singular")
    void singular() {
        assertThat(new ParseResult(1, 1, 0, 0, 0).summary()).isEqualTo("1 transaction added.");
    }

    @Test
    @DisplayName("several are plural")
    void plural() {
        assertThat(new ParseResult(5, 5, 0, 0, 0).summary()).isEqualTo("5 transactions added.");
    }

    @Test
    @DisplayName("merges are named, or the numbers would not add up on screen")
    void merges() {
        assertThat(new ParseResult(5, 3, 2, 0, 0).summary())
                .isEqualTo("3 transactions added, 2 were already recorded.");
        assertThat(new ParseResult(2, 1, 1, 0, 0).summary())
                .isEqualTo("1 transaction added, 1 was already recorded.");
    }

    @Test
    @DisplayName("failures are never hidden behind a success count")
    void failures() {
        assertThat(new ParseResult(4, 3, 0, 0, 1).summary())
                .isEqualTo("3 transactions added, 1 could not be read.");
        assertThat(new ParseResult(6, 3, 1, 0, 2).summary())
                .isEqualTo("3 transactions added, 1 was already recorded, 2 could not be read.");
    }

    @Test
    @DisplayName("reading nothing successfully still reports the attempt")
    void allFailed() {
        assertThat(new ParseResult(3, 0, 0, 0, 3).summary())
                .isEqualTo("0 transactions added, 3 could not be read.");
    }

    @Test
    @DisplayName("the queue knows whether there is anything to do")
    void queue() {
        assertThat(new ParseQueue(0, 0, 10).hasWork()).isFalse();
        assertThat(new ParseQueue(3, 0, 10).hasWork()).isTrue();
        // Failures alone are not work: they need a rule change, not a retry.
        assertThat(new ParseQueue(0, 4, 10).hasWork()).isFalse();
    }
}
