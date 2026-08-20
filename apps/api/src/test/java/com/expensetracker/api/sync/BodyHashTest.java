package com.expensetracker.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Fingerprinting a message for deduplication")
class BodyHashTest {

    @Nested
    @DisplayName("The same message is the same fingerprint")
    class Stable {

        @Test
        @DisplayName("however the transport wrapped the lines")
        void ignoresLineWrapping() {
            String a = "Rs.450.00 has been debited from your account XX1234\non 12-05-24.";
            String b = "Rs.450.00 has been debited from your account XX1234 on 12-05-24.";
            assertThat(BodyHash.of("Alert", a)).isEqualTo(BodyHash.of("Alert", b));
        }

        @Test
        @DisplayName("however the template cased it")
        void ignoresCase() {
            assertThat(BodyHash.of("ALERT", "RS.450 DEBITED"))
                    .isEqualTo(BodyHash.of("alert", "rs.450 debited"));
        }

        @Test
        @DisplayName("with invisible HTML spaces or without them")
        void ignoresNonBreakingSpaces() {
            assertThat(BodyHash.of("Alert", "Rs.\u00a0450 debited"))
                    .isEqualTo(BodyHash.of("Alert", "Rs. 450 debited"));
        }

        @Test
        @DisplayName("with a zero-width character the bank left in")
        void ignoresZeroWidth() {
            assertThat(BodyHash.of("Alert", "Rs.450\u200b debited"))
                    .isEqualTo(BodyHash.of("Alert", "Rs.450 debited"));
        }

        @Test
        @DisplayName("with trailing whitespace or without it")
        void ignoresTrailingSpace() {
            assertThat(BodyHash.of("Alert ", "Rs.450 debited   \n"))
                    .isEqualTo(BodyHash.of("Alert", "Rs.450 debited"));
        }
    }

    @Nested
    @DisplayName("Different payments are different fingerprints")
    class Distinguishes {

        @Test
        @DisplayName("a different amount")
        void amount() {
            assertThat(BodyHash.of("Alert", "Rs.450 debited at SWIGGY"))
                    .isNotEqualTo(BodyHash.of("Alert", "Rs.451 debited at SWIGGY"));
        }

        @Test
        @DisplayName("a different date")
        void date() {
            assertThat(BodyHash.of("Alert", "Rs.450 debited on 12-05-24"))
                    .isNotEqualTo(BodyHash.of("Alert", "Rs.450 debited on 13-05-24"));
        }

        @Test
        @DisplayName("a different merchant")
        void merchant() {
            assertThat(BodyHash.of("Alert", "Rs.450 debited at SWIGGY"))
                    .isNotEqualTo(BodyHash.of("Alert", "Rs.450 debited at ZOMATO"));
        }

        @Test
        @DisplayName("a different reference number, which is all that separates two identical payments")
        void referenceNumber() {
            assertThat(BodyHash.of("Alert", "Rs.450 debited. Ref 998811."))
                    .isNotEqualTo(BodyHash.of("Alert", "Rs.450 debited. Ref 998812."));
        }

        @Test
        @DisplayName("a different subject, even when the body is boilerplate")
        void subjectMatters() {
            String body = "View this transaction in the app.";
            assertThat(BodyHash.of("Rs 250 debited", body))
                    .isNotEqualTo(BodyHash.of("Rs 900 debited", body));
        }
    }

    @Nested
    @DisplayName("Edges")
    class Edges {

        @Test
        @DisplayName("null is handled rather than thrown at")
        void nulls() {
            assertThat(BodyHash.of(null, null)).isNotBlank();
            assertThat(BodyHash.of(null, "body")).isNotEqualTo(BodyHash.of("body", null));
        }

        @Test
        @DisplayName("the subject and body cannot be swapped into the same hash")
        void notConcatenationCollision() {
            // Without a separator, ("ab", "c") and ("a", "bc") would collide.
            assertThat(BodyHash.of("ab", "c")).isNotEqualTo(BodyHash.of("a", "bc"));
        }

        @Test
        @DisplayName("the fingerprint is a fixed-length hex digest")
        void shape() {
            assertThat(BodyHash.of("Alert", "Rs.450 debited"))
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }
    }
}
