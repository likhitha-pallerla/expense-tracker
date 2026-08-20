package com.expensetracker.api.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private static String freshKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static final String USER = UUID.randomUUID().toString();
    private static final String REFRESH_TOKEN =
            "1//0gK9xQfake-refresh-token-with-slashes/and+plus=signs";

    private final TokenCipher cipher = TokenCipher.fromBase64Key(freshKey());

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void recovers_the_original_token() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            assertThat(cipher.decrypt(sealed, USER)).isEqualTo(REFRESH_TOKEN);
        }

        @Test
        void survives_characters_that_appear_in_real_tokens() {
            String awkward = "a.b.c/d+e=f_g-h~i j\n\t\"quoted\" 🙂 ünïcödé";
            assertThat(cipher.decrypt(cipher.encrypt(awkward, USER), USER)).isEqualTo(awkward);
        }

        @Test
        void handles_a_long_token() {
            String long_token = "x".repeat(8000);
            assertThat(cipher.decrypt(cipher.encrypt(long_token, USER), USER)).isEqualTo(long_token);
        }

        @Test
        void passes_null_through_rather_than_encrypting_the_word_null() {
            assertThat(cipher.encrypt(null, USER)).isNull();
            assertThat(cipher.decrypt(null, USER)).isNull();
        }
    }

    @Nested
    @DisplayName("the ciphertext gives nothing away")
    class Opacity {

        @Test
        void does_not_contain_the_plaintext() {
            assertThat(cipher.encrypt(REFRESH_TOKEN, USER)).doesNotContain(REFRESH_TOKEN);
        }

        /**
         * A fresh IV every time. Reusing one under the same key in GCM leaks the
         * xor of two plaintexts and destroys the authentication guarantee, so
         * this is the single most important property here.
         */
        @Test
        void encrypting_twice_gives_two_different_ciphertexts() {
            assertThat(cipher.encrypt(REFRESH_TOKEN, USER))
                    .isNotEqualTo(cipher.encrypt(REFRESH_TOKEN, USER));
        }

        @Test
        void two_identical_tokens_are_not_recognisable_as_identical() {
            String first = cipher.encrypt("same-token", USER);
            String second = cipher.encrypt("same-token", USER);
            assertThat(first.split("\\.")[2]).isNotEqualTo(second.split("\\.")[2]);
        }
    }

    @Nested
    @DisplayName("the envelope")
    class Envelope {

        @Test
        void records_the_version_so_keys_can_be_rotated_later() {
            assertThat(cipher.encrypt(REFRESH_TOKEN, USER)).startsWith("v1.");
        }

        @Test
        void has_three_parts() {
            assertThat(cipher.encrypt(REFRESH_TOKEN, USER).split("\\.")).hasSize(3);
        }

        /** URL-safe base64, so a token can never break a query string or a log. */
        @Test
        void uses_url_safe_base64() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            assertThat(sealed).doesNotContain("+").doesNotContain("/").doesNotContain("=");
        }

        @Test
        void rejects_a_malformed_envelope() {
            assertThatThrownBy(() -> cipher.decrypt("not-an-envelope", USER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("envelope");
        }

        @Test
        void rejects_a_version_it_does_not_understand() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            String forged = "v2" + sealed.substring(2);
            assertThatThrownBy(() -> cipher.decrypt(forged, USER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("version");
        }
    }

    @Nested
    @DisplayName("tampering")
    class Tampering {

        @Test
        void a_changed_ciphertext_fails_rather_than_decrypting_to_rubbish() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            String[] parts = sealed.split("\\.");
            char last = parts[2].charAt(parts[2].length() - 1);
            String flipped = parts[2].substring(0, parts[2].length() - 1) + (last == 'A' ? 'B' : 'A');

            assertThatThrownBy(() -> cipher.decrypt(parts[0] + "." + parts[1] + "." + flipped, USER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be decrypted");
        }

        @Test
        void a_changed_iv_fails_too() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            String[] parts = sealed.split("\\.");
            char first = parts[1].charAt(0);
            String flipped = (first == 'A' ? 'B' : 'A') + parts[1].substring(1);

            assertThatThrownBy(() -> cipher.decrypt(parts[0] + "." + flipped + "." + parts[2], USER))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void truncating_the_ciphertext_fails() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            String[] parts = sealed.split("\\.");
            String cut = parts[2].substring(0, parts[2].length() - 4);

            assertThatThrownBy(() -> cipher.decrypt(parts[0] + "." + parts[1] + "." + cut, USER))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("tokens are bound to their owner")
    class Binding {

        /**
         * The point of the binding: someone with write access to the table but
         * no key cannot move a working mailbox connection onto their own
         * account by copying a row.
         */
        @Test
        void another_user_cannot_decrypt_it() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            String attacker = UUID.randomUUID().toString();

            assertThatThrownBy(() -> cipher.decrypt(sealed, attacker))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("could not be decrypted");
        }

        @Test
        void a_near_miss_on_the_owner_is_still_a_miss() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            String almost = USER.substring(0, USER.length() - 1)
                    + (USER.endsWith("a") ? "b" : "a");

            assertThatThrownBy(() -> cipher.decrypt(sealed, almost))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("keys")
    class Keys {

        @Test
        void a_different_key_cannot_read_the_ciphertext() {
            String sealed = cipher.encrypt(REFRESH_TOKEN, USER);
            TokenCipher other = TokenCipher.fromBase64Key(freshKey());

            assertThatThrownBy(() -> other.decrypt(sealed, USER))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void rejects_a_key_of_the_wrong_length() {
            String short_key = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> TokenCipher.fromBase64Key(short_key))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 bytes");
        }

        @Test
        void rejects_a_key_that_is_not_base64() {
            assertThatThrownBy(() -> TokenCipher.fromBase64Key("this is not base64!!"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base64");
        }

        /**
         * Missing configuration must not stop the API booting — budgets and
         * imports have nothing to do with mailboxes — so the complaint is
         * deferred to the moment a token would actually be handled.
         */
        @Test
        void an_absent_key_defers_the_failure_instead_of_refusing_to_start() {
            TokenCipher unconfigured = TokenCipher.fromBase64Key("  ");

            assertThat(unconfigured.isConfigured()).isFalse();
            assertThatThrownBy(() -> unconfigured.encrypt(REFRESH_TOKEN, USER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TOKEN_ENCRYPTION_KEY");
        }

        @Test
        void a_configured_cipher_says_so() {
            assertThat(cipher.isConfigured()).isTrue();
        }
    }
}
