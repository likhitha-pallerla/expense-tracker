package com.expensetracker.api.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmountsTest {

    @Test
    @DisplayName("reads a plain figure")
    void plain() {
        assertThat(Amounts.parse("500")).contains(new BigDecimal("500.00"));
        assertThat(Amounts.parse("500.50")).contains(new BigDecimal("500.50"));
    }

    @Test
    @DisplayName("reads lakh grouping, which no locale parser gets right")
    void lakhGrouping() {
        assertThat(Amounts.parse("1,23,456.78")).contains(new BigDecimal("123456.78"));
    }

    @Test
    @DisplayName("reads thousand grouping too, so the same rule works abroad")
    void thousandGrouping() {
        assertThat(Amounts.parse("123,456.78")).contains(new BigDecimal("123456.78"));
    }

    @Test
    @DisplayName("ignores surrounding space")
    void spaced() {
        assertThat(Amounts.parse("  1,500.00  ")).contains(new BigDecimal("1500.00"));
    }

    @Test
    @DisplayName("rejects zero, which is a notification rather than a payment")
    void zero() {
        assertThat(Amounts.parse("0")).isEmpty();
        assertThat(Amounts.parse("0.00")).isEmpty();
    }

    @Test
    @DisplayName("rejects an implausible figure rather than distorting every chart")
    void tooLarge() {
        assertThat(Amounts.parse("999999999")).isEmpty();
    }

    @Test
    @DisplayName("rejects text that is not a number")
    void notANumber() {
        assertThat(Amounts.parse("many")).isEmpty();
        assertThat(Amounts.parse("")).isEmpty();
        assertThat(Amounts.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("rejects more than two decimal places, which is not money")
    void tooPrecise() {
        assertThat(Amounts.parse("100.005")).isEmpty();
    }

    @Test
    @DisplayName("rejects a trailing separator, which came from the sentence")
    void trailingSeparator() {
        assertThat(Amounts.parse("500.")).isEmpty();
        assertThat(Amounts.parse("500,")).isEmpty();
    }

    @Test
    @DisplayName("rejects a negative, because direction carries the sign")
    void negative() {
        assertThat(Amounts.parse("-500")).isEmpty();
    }
}
