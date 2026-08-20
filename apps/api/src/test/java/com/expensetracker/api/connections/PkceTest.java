package com.expensetracker.api.connections;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PkceTest {

    @Nested
    @DisplayName("the verifier")
    class Verifier {

        /** RFC 7636 allows 43-128 characters; 32 random bytes gives exactly 43. */
        @Test
        void is_the_length_the_spec_requires() {
            assertThat(Pkce.newVerifier()).hasSize(43);
        }

        @Test
        void uses_only_characters_the_spec_permits() {
            assertThat(Pkce.newVerifier()).matches("[A-Za-z0-9\\-._~]+");
        }

        /**
         * A predictable verifier would defeat the whole mechanism, so this
         * checks for actual randomness rather than merely for a non-empty
         * string.
         */
        @Test
        void is_different_every_time() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                seen.add(Pkce.newVerifier());
            }
            assertThat(seen).hasSize(500);
        }
    }

    @Nested
    @DisplayName("the state")
    class State {

        @Test
        void is_url_safe() {
            assertThat(Pkce.newState()).matches("[A-Za-z0-9\\-_]+");
        }

        @Test
        void is_different_every_time() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                seen.add(Pkce.newState());
            }
            assertThat(seen).hasSize(500);
        }
    }

    @Nested
    @DisplayName("the S256 challenge")
    class Challenge {

        /** The example pair from RFC 7636 appendix B. */
        @Test
        void matches_the_worked_example_in_the_rfc() {
            String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
            assertThat(Pkce.challengeFor(verifier))
                    .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        }

        @Test
        void is_the_base64url_of_the_sha256_of_the_verifier() throws Exception {
            String verifier = Pkce.newVerifier();
            byte[] expected = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));

            assertThat(Pkce.challengeFor(verifier))
                    .isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(expected));
        }

        @Test
        void is_not_the_verifier_itself() {
            String verifier = Pkce.newVerifier();
            assertThat(Pkce.challengeFor(verifier)).isNotEqualTo(verifier);
        }

        @Test
        void carries_no_padding_that_would_need_url_encoding() {
            assertThat(Pkce.challengeFor(Pkce.newVerifier())).doesNotContain("=");
        }

        @Test
        void is_stable_for_the_same_verifier() {
            String verifier = Pkce.newVerifier();
            assertThat(Pkce.challengeFor(verifier)).isEqualTo(Pkce.challengeFor(verifier));
        }
    }
}
