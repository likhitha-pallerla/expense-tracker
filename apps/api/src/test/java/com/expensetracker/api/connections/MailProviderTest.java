package com.expensetracker.api.connections;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.expensetracker.api.connections.OAuthProperties.Provider;

class MailProviderTest {

    @Nested
    @DisplayName("parsing the provider from a URL")
    class Parsing {

        @Test
        void accepts_the_keys_we_publish() {
            assertThat(MailProvider.from("gmail")).contains(MailProvider.GMAIL);
            assertThat(MailProvider.from("outlook")).contains(MailProvider.OUTLOOK);
        }

        @Test
        void is_forgiving_about_case_and_spacing() {
            assertThat(MailProvider.from(" GMAIL ")).contains(MailProvider.GMAIL);
        }

        /**
         * Anything else is empty rather than a default. Guessing would mean a
         * typo in a URL silently started a handshake with the wrong provider.
         */
        @Test
        void refuses_anything_else() {
            assertThat(MailProvider.from("yahoo")).isEmpty();
            assertThat(MailProvider.from("")).isEmpty();
            assertThat(MailProvider.from(null)).isEmpty();
        }

        /** These exist in the database enum but have no OAuth handshake. */
        @Test
        void refuses_the_non_mailbox_sources() {
            assertThat(MailProvider.from("csv_import")).isEmpty();
            assertThat(MailProvider.from("android_sms")).isEmpty();
            assertThat(MailProvider.from("manual")).isEmpty();
        }
    }

    @Nested
    @DisplayName("scopes")
    class Scopes {

        @Test
        void gmail_asks_only_for_read_access() {
            assertThat(MailProvider.GMAIL.scopes())
                    .contains("https://www.googleapis.com/auth/gmail.readonly")
                    .noneMatch(scope -> scope.contains("gmail.modify"))
                    .noneMatch(scope -> scope.contains("gmail.send"))
                    .noneMatch(scope -> scope.contains("mail.google.com"));
        }

        /** Without offline_access Microsoft issues no refresh token at all. */
        @Test
        void outlook_asks_for_offline_access() {
            assertThat(MailProvider.OUTLOOK.scopes()).contains("offline_access");
        }

        @Test
        void outlook_asks_only_for_read_access() {
            assertThat(MailProvider.OUTLOOK.scopes())
                    .contains("https://graph.microsoft.com/Mail.Read")
                    .noneMatch(scope -> scope.contains("Mail.Send"))
                    .noneMatch(scope -> scope.contains("Mail.ReadWrite"));
        }

        @Test
        void both_ask_for_the_address_so_the_connection_can_be_labelled() {
            assertThat(MailProvider.GMAIL.scopes()).contains("email");
            assertThat(MailProvider.OUTLOOK.scopes()).contains("email");
        }

        @Test
        void the_scope_parameter_is_space_separated() {
            assertThat(MailProvider.GMAIL.scopeParameter())
                    .isEqualTo(String.join(" ", MailProvider.GMAIL.scopes()));
        }
    }

    @Nested
    @DisplayName("finding the mailbox address")
    class Address {

        @Test
        void reads_googles_field() {
            assertThat(MailProvider.GMAIL.addressFrom(Map.of("email", "someone@gmail.com")))
                    .isEqualTo("someone@gmail.com");
        }

        @Test
        void reads_microsofts_field() {
            assertThat(MailProvider.OUTLOOK.addressFrom(Map.of("mail", "someone@work.com")))
                    .isEqualTo("someone@work.com");
        }

        /**
         * Personal Microsoft accounts have no Exchange mailbox, so Graph
         * returns a null `mail` and the address people recognise is the
         * principal name.
         */
        @Test
        void falls_back_to_the_principal_name_for_personal_accounts() {
            Map<String, Object> graph = new HashMap<>();
            graph.put("mail", null);
            graph.put("userPrincipalName", "someone@outlook.com");

            assertThat(MailProvider.OUTLOOK.addressFrom(graph)).isEqualTo("someone@outlook.com");
        }

        @Test
        void prefers_the_real_mailbox_when_both_are_present() {
            assertThat(MailProvider.OUTLOOK.addressFrom(Map.of(
                    "mail", "real@work.com",
                    "userPrincipalName", "alias@work.onmicrosoft.com")))
                    .isEqualTo("real@work.com");
        }

        /**
         * A missing address must not fail the handshake — the connection works,
         * it is just harder to label.
         */
        @Test
        void copes_with_nothing_useful_coming_back() {
            assertThat(MailProvider.GMAIL.addressFrom(Map.of())).isNull();
            assertThat(MailProvider.GMAIL.addressFrom(null)).isNull();
            assertThat(MailProvider.OUTLOOK.addressFrom(Map.of("mail", ""))).isNull();
        }

        @Test
        void ignores_a_field_of_the_wrong_shape() {
            assertThat(MailProvider.GMAIL.addressFrom(Map.of("email", 42))).isNull();
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        private final OAuthProperties properties = new OAuthProperties(
                "https://api.example.com/", "https://app.example.com/", 600,
                new Provider("gid", "gsecret", "https://auth", "https://token", "https://me", null),
                new Provider("", "", "https://auth", "https://token", "https://me", null));

        @Test
        void the_redirect_uri_names_the_provider() {
            assertThat(properties.redirectUri(MailProvider.GMAIL))
                    .isEqualTo("https://api.example.com/api/connections/callback/gmail");
        }

        /** A double slash would not match the registered redirect. */
        @Test
        void trailing_slashes_in_configuration_do_not_reach_the_url() {
            assertThat(properties.apiBase()).isEqualTo("https://api.example.com");
            assertThat(properties.webBase()).isEqualTo("https://app.example.com");
        }

        @Test
        void a_provider_without_credentials_is_not_configured() {
            assertThat(properties.forProvider(MailProvider.GMAIL).isConfigured()).isTrue();
            assertThat(properties.forProvider(MailProvider.OUTLOOK).isConfigured()).isFalse();
        }

        @Test
        void a_nonsense_ttl_falls_back_to_ten_minutes() {
            OAuthProperties zero = new OAuthProperties("a", "b", 0, null, null);
            assertThat(zero.stateTtlSeconds()).isEqualTo(600);
        }
    }

    @Nested
    @DisplayName("disconnecting")
    class Disconnecting {

        /**
         * The regression this exists for. A phone stores no token and has no
         * provider to notify, and an earlier version treated that as an error
         * rather than a skip — which meant the single device able to read a
         * person's messages was the one connection they could not switch off.
         */
        @Test
        void a_phone_is_removed_without_calling_any_provider() {
            assertThat(ConnectionService.shouldRevokeUpstream("android_sms", null, true)).isFalse();
            assertThat(ConnectionService.shouldRevokeUpstream("csv_import", null, true)).isFalse();
            assertThat(ConnectionService.shouldRevokeUpstream("manual", null, true)).isFalse();
        }

        @Test
        void a_mailbox_with_a_token_is_revoked_upstream() {
            assertThat(ConnectionService.shouldRevokeUpstream("gmail", "sealed", true)).isTrue();
            assertThat(ConnectionService.shouldRevokeUpstream("outlook", "sealed", true)).isTrue();
        }

        /** Nothing to hand back, so nothing to call — but still deletable. */
        @Test
        void a_mailbox_without_a_stored_token_is_just_deleted() {
            assertThat(ConnectionService.shouldRevokeUpstream("gmail", null, true)).isFalse();
        }

        /**
         * Without the key the token cannot be decrypted, so there is nothing to
         * send. Losing the key must not make connections permanent.
         */
        @Test
        void an_unreadable_token_does_not_block_removal() {
            assertThat(ConnectionService.shouldRevokeUpstream("gmail", "sealed", false)).isFalse();
        }
    }

    @Nested
    @DisplayName("the return path")
    class ReturnPath {
        @Test
        void keeps_a_path_inside_the_app() {
            assertThat(ConnectionService.safeReturnPath("/settings/connections"))
                    .isEqualTo("/settings/connections");
        }

        @Test
        void falls_back_when_nothing_is_asked_for() {
            assertThat(ConnectionService.safeReturnPath(null)).isEqualTo("/connections");
            assertThat(ConnectionService.safeReturnPath("  ")).isEqualTo("/connections");
        }

        /**
         * The open-redirect guard. Anything that could send the browser to
         * another origin after a successful authorisation is discarded.
         */
        @Test
        void refuses_anything_that_leaves_our_origin() {
            assertThat(ConnectionService.safeReturnPath("https://evil.example.com"))
                    .isEqualTo("/connections");
            assertThat(ConnectionService.safeReturnPath("//evil.example.com"))
                    .isEqualTo("/connections");
            assertThat(ConnectionService.safeReturnPath("javascript:alert(1)"))
                    .isEqualTo("/connections");
            assertThat(ConnectionService.safeReturnPath("connections"))
                    .isEqualTo("/connections");
        }
    }
}
