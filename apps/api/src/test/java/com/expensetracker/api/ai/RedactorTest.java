package com.expensetracker.api.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Two obligations pull against each other here, so both are tested.
 *
 * <p>Everything under {@link WhatMustGo} is a privacy failure if it regresses.
 * Everything under {@link WhatMustSurvive} is a parsing failure if it
 * regresses — and a redactor that quietly destroys the amount would look
 * perfectly safe while making the whole feature useless.
 */
class RedactorTest {

    @Nested
    @DisplayName("what must go")
    class WhatMustGo {

        @Test
        void full_card_numbers_in_any_position() {
            assertThat(Redactor.scrub("Card 4111111111111111 used at AMAZON"))
                    .doesNotContain("4111111111111111")
                    .contains("XX1111");

            // Spaced and hyphenated, because that is how they get written.
            assertThat(Redactor.scrub("paid with 4111 1111 1111 1111"))
                    .doesNotContain("4111 1111")
                    .contains("XX1111");
            assertThat(Redactor.scrub("card 5500-0000-0000-0004"))
                    .doesNotContain("5500-0000")
                    .contains("XX0004");
        }

        @Test
        void account_numbers_that_say_they_are_account_numbers() {
            assertThat(Redactor.scrub("debited from account 123456789012"))
                    .doesNotContain("123456789012")
                    .contains("XX9012");
            assertThat(Redactor.scrub("A/c No. 50100234567890 debited"))
                    .doesNotContain("50100234567890")
                    .contains("XX7890");
        }

        @Test
        void email_addresses() {
            assertThat(Redactor.scrub("Statement sent to ravi.kumar@gmail.com"))
                    .doesNotContain("ravi.kumar")
                    .doesNotContain("gmail.com")
                    .contains("[email]");
        }

        @Test
        void mobile_numbers() {
            assertThat(Redactor.scrub("Call 9812345678 for help"))
                    .doesNotContain("9812345678")
                    .contains("[phone]");
            assertThat(Redactor.scrub("Contact +91 9812345678"))
                    .doesNotContain("9812345678")
                    .contains("[phone]");
        }

        /**
         * A running balance is a direct statement of what somebody has and is
         * never needed to record a payment, so it is the easiest thing in the
         * message to justify removing.
         */
        @Test
        void balances_however_they_are_labelled() {
            assertThat(Redactor.scrub("Avl Bal Rs 45,231.19"))
                    .doesNotContain("45,231.19")
                    .contains("[balance]");
            assertThat(Redactor.scrub("Available balance: INR 1,20,000"))
                    .doesNotContain("1,20,000")
                    .contains("[balance]");
            assertThat(Redactor.scrub("Closing balance 9832.50"))
                    .doesNotContain("9832.50")
                    .contains("[balance]");
        }

        /**
         * These are rejected far upstream by SmsFilter. Handling them again
         * here is deliberate: a code that has slipped one gate should not sail
         * through the next.
         */
        @Test
        void one_time_passwords_even_though_they_should_never_reach_here() {
            assertThat(Redactor.scrub("724193 is the OTP for your payment"))
                    .doesNotContain("724193")
                    .contains("[code]");
            assertThat(Redactor.scrub("Your OTP is 481920"))
                    .doesNotContain("481920")
                    .contains("[code]");
        }

        /**
         * The handle names a payment app and is harmless; the local part is a
         * person's mobile number and is not.
         */
        @Test
        void the_phone_number_inside_a_upi_address_but_not_the_handle() {
            String out = Redactor.scrub("paid to 9812345678@ybl");
            assertThat(out).doesNotContain("9812345678").contains("[phone]@ybl");
        }
    }

    @Nested
    @DisplayName("what must survive")
    class WhatMustSurvive {

        /**
         * The single most important assertion in this class. A redactor that
         * eats the amount would pass every privacy test above and make the
         * fallback parser incapable of reading anything.
         */
        @Test
        void the_amount_of_the_payment() {
            assertThat(Redactor.scrub("Rs 450.00 debited from a/c XX1234"))
                    .contains("450.00");
            assertThat(Redactor.scrub("INR 1,299.00 spent on card XX9012"))
                    .contains("1,299.00");
            assertThat(Redactor.scrub("Rs. 2,50,000 credited"))
                    .contains("2,50,000");
        }

        @Test
        void an_amount_that_sits_next_to_a_balance() {
            String out = Redactor.scrub(
                    "Rs 450.00 debited from a/c XX1234. Avl Bal Rs 45,231.19");
            assertThat(out).contains("450.00").doesNotContain("45,231.19");
        }

        @Test
        void the_direction_verb_and_the_merchant() {
            String out = Redactor.scrub("Rs 450 debited to VPA chaiwala@ybl");
            assertThat(out).contains("debited").contains("chaiwala@ybl");
        }

        @Test
        void the_last_four_digits_an_account_is_matched_by() {
            assertThat(Redactor.scrub("debited from a/c XX1234")).contains("1234");
            assertThat(Redactor.scrub("Card XX9012 used")).contains("9012");
        }

        /**
         * The deliberate trade-off, stated as a test so it cannot be changed by
         * accident. A reference is the strongest duplicate signal this system
         * has; losing it would weaken dedup on every AI-parsed message.
         */
        @Test
        void the_payment_reference_which_is_the_strongest_dedup_signal() {
            assertThat(Redactor.scrub("UPI Ref 402512345678")).contains("402512345678");
            assertThat(Redactor.scrub("UTR 234567890123")).contains("234567890123");
            assertThat(Redactor.scrub("Txn ID 987654321098")).contains("987654321098");
        }

        @Test
        void the_date() {
            assertThat(Redactor.scrub("on 04-02-25 at AMAZON")).contains("04-02-25");
            assertThat(Redactor.scrub("on 2025-02-04")).contains("2025-02-04");
        }
    }

    @Nested
    @DisplayName("realistic messages end to end")
    class WholeMessages {

        @Test
        void a_upi_debit_keeps_everything_needed_and_loses_the_balance() {
            String out = Redactor.scrub(
                    "Rs 450.00 debited from a/c XX1234 on 04-02-25 to VPA chaiwala@ybl. "
                            + "Ref 402512345678. Avl Bal Rs 45,231.19");

            assertThat(out)
                    .contains("450.00")
                    .contains("debited")
                    .contains("1234")
                    .contains("04-02-25")
                    .contains("chaiwala@ybl")
                    .contains("402512345678");
            assertThat(out).doesNotContain("45,231.19");
        }

        @Test
        void a_card_alert_loses_the_pan_and_keeps_the_merchant() {
            String out = Redactor.scrub(
                    "INR 1,299.00 spent on ICICI Bank Card 4111111111111111 at AMAZON "
                            + "on 04-02-25. Avl Lmt INR 48,701");

            assertThat(out).contains("1,299.00").contains("AMAZON").contains("XX1111");
            assertThat(out).doesNotContain("4111111111111111");
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        void copes_with_nothing() {
            assertThat(Redactor.scrub(null)).isNull();
            assertThat(Redactor.scrub("")).isEmpty();
            assertThat(Redactor.scrub("   ")).isEqualTo("   ");
        }

        @Test
        void leaves_text_with_nothing_to_hide_alone() {
            String clean = "Payment of Rs 100 to SWIGGY";
            assertThat(Redactor.scrub(clean)).isEqualTo(clean);
            assertThat(Redactor.changed(clean, Redactor.scrub(clean))).isFalse();
        }

        @Test
        void reports_when_it_removed_something() {
            String dirty = "call 9812345678";
            assertThat(Redactor.changed(dirty, Redactor.scrub(dirty))).isTrue();
        }

        /** Short numbers are not accounts; masking them would destroy amounts. */
        @Test
        void does_not_mask_short_numbers() {
            assertThat(Redactor.scrub("Rs 450 spent")).contains("450");
            assertThat(Redactor.scrub("12 items")).contains("12");
        }

        /**
         * Redaction has to be safe to apply twice — it runs on the way into the
         * prompt and could run again on anything cached or retried.
         */
        @Test
        void is_idempotent() {
            String message = "Rs 450.00 debited from a/c 123456789012 to 9812345678@ybl. "
                    + "Avl Bal Rs 45,231.19. Contact ravi@bank.com";
            String once = Redactor.scrub(message);
            assertThat(Redactor.scrub(once)).isEqualTo(once);
        }
    }
}
