package com.expensetracker.api.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("who a payment alert may come from")
class SenderTrustTest {

    private static final Set<String> NOTHING_TRUSTED = Set.of();

    @Nested
    @DisplayName("reading the address")
    class Reading {

        @Test
        void a_bare_address() {
            assertThat(SenderTrust.domainOf("alerts@hdfcbank.net")).contains("hdfcbank.net");
        }

        @Test
        void the_display_form_a_mail_client_sends() {
            assertThat(SenderTrust.domainOf("HDFC Bank <alerts@hdfcbank.net>"))
                    .contains("hdfcbank.net");
        }

        @Test
        void case_does_not_matter() {
            assertThat(SenderTrust.domainOf("Alerts@HDFCBank.NET")).contains("hdfcbank.net");
        }

        @Test
        void a_trailing_dot_is_the_same_domain() {
            assertThat(SenderTrust.domainOf("alerts@hdfcbank.net.")).contains("hdfcbank.net");
        }

        @Test
        void nothing_useful_is_nothing() {
            assertThat(SenderTrust.domainOf(null)).isEmpty();
            assertThat(SenderTrust.domainOf("")).isEmpty();
            assertThat(SenderTrust.domainOf("   ")).isEmpty();
            assertThat(SenderTrust.domainOf("no-at-sign")).isEmpty();
            assertThat(SenderTrust.domainOf("user@localhost")).isEmpty();
        }

        /**
         * A forged header may carry two addresses so that a naive parser reads
         * one while the mail client shows the other. The one in angle brackets
         * is what the user sees, so it is what must be judged.
         */
        @Test
        void when_a_header_holds_two_addresses_the_visible_one_is_judged() {
            assertThat(SenderTrust.domainOf("alerts@hdfcbank.net <attacker@evil.example>"))
                    .contains("evil.example");
        }

        /**
         * "hdfcbank.net" written with a Cyrillic a is a different string that
         * renders identically. Normalising it would be guessing at intent;
         * refusing it means the message is quarantined and a human looks.
         */
        @Test
        void a_lookalike_domain_in_another_alphabet_is_not_read_at_all() {
            assertThat(SenderTrust.domainOf("alerts@hdfcb\u0430nk.net")).isEmpty();
        }
    }

    @Nested
    @DisplayName("recognised senders")
    class Recognised {

        @Test
        void a_known_bank_is_accepted() {
            assertThat(SenderTrust.judge("alerts@hdfcbank.net", NOTHING_TRUSTED))
                    .isEqualTo(SenderTrust.Verdict.KNOWN_INSTITUTION);
        }

        /** Banks send from subdomains, and refusing those would refuse the bank. */
        @Test
        void so_is_a_subdomain_of_one() {
            assertThat(SenderTrust.judge("no-reply@emailer.icicibank.com", NOTHING_TRUSTED))
                    .isEqualTo(SenderTrust.Verdict.KNOWN_INSTITUTION);
        }

        @Test
        void a_domain_the_user_added_is_accepted() {
            assertThat(SenderTrust.judge("alerts@mysmallbank.co.in", Set.of("mysmallbank.co.in")))
                    .isEqualTo(SenderTrust.Verdict.TRUSTED_BY_USER);
        }

        @Test
        void and_its_subdomains_too() {
            assertThat(SenderTrust.judge("x@alerts.mysmallbank.co.in", Set.of("mysmallbank.co.in")))
                    .isEqualTo(SenderTrust.Verdict.TRUSTED_BY_USER);
        }
    }

    @Nested
    @DisplayName("senders that must not be accepted")
    class Refused {

        /**
         * The attack this class exists to stop. Matching by suffix alone would
         * accept this, because it does end with "hdfcbank.net".
         */
        @Test
        void a_domain_that_merely_ends_with_a_bank_name_is_not_that_bank() {
            assertThat(SenderTrust.judge("alerts@myhdfcbank.net", NOTHING_TRUSTED))
                    .isEqualTo(SenderTrust.Verdict.UNRECOGNISED);
        }

        /** Nor is one that merely begins with it. */
        @Test
        void nor_is_a_domain_that_puts_the_bank_name_on_the_left() {
            assertThat(SenderTrust.judge("alerts@hdfcbank.net.attacker.example", NOTHING_TRUSTED))
                    .isEqualTo(SenderTrust.Verdict.UNRECOGNISED);
        }

        @Test
        void a_domain_containing_a_bank_name_in_the_middle_is_not_it_either() {
            assertThat(SenderTrust.judge("x@secure-hdfcbank.net.co", NOTHING_TRUSTED))
                    .isEqualTo(SenderTrust.Verdict.UNRECOGNISED);
        }

        /**
         * The reason consumer providers are refused outright: anyone can have
         * an address at one, so trusting the domain trusts everybody.
         */
        @Test
        void a_free_webmail_address_is_never_an_institution() {
            assertThat(SenderTrust.judge("hdfcbank.alerts@gmail.com", NOTHING_TRUSTED))
                    .isEqualTo(SenderTrust.Verdict.NOT_AN_INSTITUTION);
        }

        @Test
        void and_stays_refused_even_if_the_user_asks_for_it() {
            assertThat(SenderTrust.judge("someone@gmail.com", Set.of("gmail.com")))
                    .isEqualTo(SenderTrust.Verdict.NOT_AN_INSTITUTION);
        }

        @Test
        void the_same_goes_for_the_other_big_providers() {
            for (String domain : new String[] {
                    "outlook.com", "yahoo.com", "icloud.com", "protonmail.com", "rediffmail.com"}) {
                assertThat(SenderTrust.judge("a@" + domain, Set.of(domain)))
                        .as(domain)
                        .isEqualTo(SenderTrust.Verdict.NOT_AN_INSTITUTION);
            }
        }

        @Test
        void a_message_with_no_sender_at_all_is_not_accepted() {
            assertThat(SenderTrust.judge(null, NOTHING_TRUSTED))
                    .isEqualTo(SenderTrust.Verdict.NOT_AN_INSTITUTION);
        }

        @Test
        void an_unknown_sender_is_held_rather_than_accepted() {
            assertThat(SenderTrust.judge("alerts@somebank.example", NOTHING_TRUSTED))
                    .isEqualTo(SenderTrust.Verdict.UNRECOGNISED);
        }
    }

    @Nested
    @DisplayName("what may be added to a trusted list")
    class Adding {

        @Test
        void an_ordinary_domain_may_be() {
            assertThat(SenderTrust.canBeTrusted("mysmallbank.co.in")).isTrue();
        }

        @Test
        void a_consumer_provider_may_not_be() {
            assertThat(SenderTrust.canBeTrusted("gmail.com")).isFalse();
        }

        @Test
        void nor_may_a_subdomain_of_one() {
            assertThat(SenderTrust.canBeTrusted("alerts.gmail.com")).isFalse();
        }

        @Test
        void nor_may_something_that_is_not_a_domain() {
            assertThat(SenderTrust.canBeTrusted("not a domain")).isFalse();
            assertThat(SenderTrust.canBeTrusted("")).isFalse();
            assertThat(SenderTrust.canBeTrusted(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("only accepted senders may write to the ledger")
    class Outcome {

        @Test
        void recognised_and_user_trusted_are_accepted_and_nothing_else_is() {
            assertThat(SenderTrust.Verdict.KNOWN_INSTITUTION.isAccepted()).isTrue();
            assertThat(SenderTrust.Verdict.TRUSTED_BY_USER.isAccepted()).isTrue();
            assertThat(SenderTrust.Verdict.UNRECOGNISED.isAccepted()).isFalse();
            assertThat(SenderTrust.Verdict.NOT_AN_INSTITUTION.isAccepted()).isFalse();
        }
    }
}
