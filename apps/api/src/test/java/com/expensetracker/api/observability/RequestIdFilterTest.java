package com.expensetracker.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("request ids")
class RequestIdFilterTest {

    @Test
    void a_missing_header_means_a_new_id_is_generated() {
        assertThat(RequestIdFilter.sanitise(null)).isNull();
        assertThat(RequestIdFilter.sanitise("")).isNull();
        assertThat(RequestIdFilter.sanitise("   ")).isNull();
    }

    @Test
    void an_ordinary_id_passes_through_unchanged() {
        String uuid = "3f1b7e2c-9a44-4d51-b0c3-71e6a9b8c210";
        assertThat(RequestIdFilter.sanitise(uuid)).isEqualTo(uuid);
    }

    @Test
    void surrounding_whitespace_is_trimmed() {
        assertThat(RequestIdFilter.sanitise("  abc123  ")).isEqualTo("abc123");
    }

    /**
     * The point of sanitising. A caller who can put a newline in a header can
     * write their own lines into the log — inventing a successful payment, or
     * a request that never happened — and an incident review has no way to
     * tell the forged line from a real one.
     */
    @Test
    void a_newline_cannot_be_smuggled_into_the_log() {
        String forged = "abc\n2026-01-01 INFO access - POST /api/transactions -> 200";
        String cleaned = RequestIdFilter.sanitise(forged);

        assertThat(cleaned).doesNotContain("\n");
        assertThat(cleaned).doesNotContain("\r");
    }

    @Test
    void nor_a_carriage_return() {
        assertThat(RequestIdFilter.sanitise("abc\r\nDELETE")).doesNotContain("\r");
    }

    @Test
    void nor_ansi_escape_codes_that_would_rewrite_a_terminal() {
        assertThat(RequestIdFilter.sanitise("abc\u001b[31mred")).isEqualTo("abc31mred");
    }

    @Test
    void a_header_of_nothing_but_punctuation_is_treated_as_absent() {
        assertThat(RequestIdFilter.sanitise("!!!@@@###")).isNull();
    }

    /**
     * An unbounded id would let a caller push megabytes into every log line
     * the request produces.
     */
    @Test
    void an_absurdly_long_id_is_truncated() {
        String cleaned = RequestIdFilter.sanitise("a".repeat(5000));
        assertThat(cleaned).hasSize(64);
    }

    @Test
    void the_characters_a_trace_id_actually_uses_are_kept() {
        assertThat(RequestIdFilter.sanitise("00-4bf92f-a3ce929d-01"))
                .isEqualTo("00-4bf92f-a3ce929d-01");
        assertThat(RequestIdFilter.sanitise("req_1.2:3")).isEqualTo("req_1.2:3");
    }
}
