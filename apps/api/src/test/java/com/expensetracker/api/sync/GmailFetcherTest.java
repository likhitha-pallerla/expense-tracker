package com.expensetracker.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Reading Gmail")
class GmailFetcherTest {

    private static final String BASE = "https://gmail.test";

    /** Records what was asked for and replies with whatever the test set up. */
    private static class FakeHttp implements MailHttp {
        final List<String> requested = new ArrayList<>();
        final Map<String, Object> responses = new LinkedHashMap<>();
        final List<String> cursorLostFor = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> get(String url, String accessToken) {
            requested.add(url);
            for (String fragment : cursorLostFor) {
                if (url.contains(fragment)) {
                    throw new MailCursorLostException("gone");
                }
            }
            for (Map.Entry<String, Object> entry : responses.entrySet()) {
                if (url.contains(entry.getKey())) {
                    return (Map<String, Object>) entry.getValue();
                }
            }
            return Map.of();
        }
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> gmailMessage(String id, String subject, String body) {
        return Map.of(
                "id", id,
                "snippet", "snippet for " + id,
                "internalDate", "1715500000000",
                "payload", Map.of(
                        "mimeType", "text/plain",
                        "headers", List.of(
                                Map.of("name", "Subject", "value", subject),
                                Map.of("name", "From", "value", "alerts@bank.example")),
                        "body", Map.of("data", b64(body))));
    }

    @Nested
    @DisplayName("Finding the readable text")
    class Bodies {

        @Test
        @DisplayName("a simple plain-text message")
        void plainText() {
            Map<String, Object> payload = Map.of(
                    "mimeType", "text/plain",
                    "body", Map.of("data", b64("Rs.450 debited")));
            assertThat(GmailFetcher.extractBody(payload)).isEqualTo("Rs.450 debited");
        }

        @Test
        @DisplayName("prefers the plain part even when HTML comes first")
        void prefersPlainOverHtml() {
            Map<String, Object> payload = Map.of(
                    "mimeType", "multipart/alternative",
                    "parts", List.of(
                            Map.of("mimeType", "text/html",
                                    "body", Map.of("data", b64("<p>Rs.450 <b>debited</b></p>"))),
                            Map.of("mimeType", "text/plain",
                                    "body", Map.of("data", b64("Rs.450 debited")))));

            assertThat(GmailFetcher.extractBody(payload)).isEqualTo("Rs.450 debited");
        }

        @Test
        @DisplayName("falls back to HTML, reduced to words, when there is no plain part")
        void htmlOnly() {
            Map<String, Object> payload = Map.of(
                    "mimeType", "text/html",
                    "body", Map.of("data", b64("<div>Rs.450 <b>debited</b> at SWIGGY</div>")));

            assertThat(GmailFetcher.extractBody(payload))
                    .contains("Rs.450")
                    .contains("debited")
                    .contains("SWIGGY")
                    .doesNotContain("<");
        }

        @Test
        @DisplayName("digs through nested multiparts")
        void nested() {
            Map<String, Object> payload = Map.of(
                    "mimeType", "multipart/mixed",
                    "parts", List.of(
                            Map.of("mimeType", "application/pdf",
                                    "body", Map.of("attachmentId", "x")),
                            Map.of("mimeType", "multipart/alternative",
                                    "parts", List.of(
                                            Map.of("mimeType", "text/plain",
                                                    "body", Map.of("data", b64("buried but found")))))));

            assertThat(GmailFetcher.extractBody(payload)).isEqualTo("buried but found");
        }

        @Test
        @DisplayName("skips an empty plain part in favour of a real HTML one")
        void emptyPlainPart() {
            Map<String, Object> payload = Map.of(
                    "mimeType", "multipart/alternative",
                    "parts", List.of(
                            Map.of("mimeType", "text/plain", "body", Map.of("data", b64("   "))),
                            Map.of("mimeType", "text/html",
                                    "body", Map.of("data", b64("<p>Rs.450 debited</p>")))));

            assertThat(GmailFetcher.extractBody(payload)).contains("Rs.450 debited");
        }

        @Test
        @DisplayName("survives base64 that will not decode")
        void badBase64() {
            Map<String, Object> payload = Map.of(
                    "mimeType", "text/plain",
                    "body", Map.of("data", "!!!not base64!!!"));
            assertThat(GmailFetcher.extractBody(payload)).isNull();
        }

        @Test
        @DisplayName("survives a message with no payload at all")
        void noPayload() {
            assertThat(GmailFetcher.extractBody(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Reading one message")
    class OneMessage {

        @Test
        @DisplayName("takes the subject and sender from the headers, whatever their order")
        void headers() {
            Map<String, Object> raw = new LinkedHashMap<>(gmailMessage("m1", "Alert", "body"));
            MailMessage message = GmailFetcher.toMessage(raw);

            assertThat(message).isNotNull();
            assertThat(message.subject()).isEqualTo("Alert");
            assertThat(message.sender()).isEqualTo("alerts@bank.example");
            assertThat(message.providerMessageId()).isEqualTo("m1");
        }

        @Test
        @DisplayName("reads the date from Gmail's epoch milliseconds")
        void date() {
            MailMessage message = GmailFetcher.toMessage(gmailMessage("m1", "Alert", "body"));
            assertThat(message.receivedAt()).isEqualTo(Instant.ofEpochMilli(1715500000000L));
        }

        @Test
        @DisplayName("keeps a message whose date is unreadable rather than losing it")
        void unreadableDate() {
            Map<String, Object> raw = new LinkedHashMap<>(gmailMessage("m1", "Alert", "body"));
            raw.put("internalDate", "not a number");

            MailMessage message = GmailFetcher.toMessage(raw);
            assertThat(message).isNotNull();
            assertThat(message.receivedAt()).isNull();
            assertThat(message.body()).isEqualTo("body");
        }

        @Test
        @DisplayName("refuses a message with no id, which is nothing we can deduplicate")
        void noId() {
            assertThat(GmailFetcher.toMessage(Map.of("snippet", "x"))).isNull();
            assertThat(GmailFetcher.toMessage(null)).isNull();
        }
    }

    @Nested
    @DisplayName("A first sync")
    class Backfill {

        @Test
        @DisplayName("searches by date and fetches each message it finds")
        void searchesAndHydrates() {
            FakeHttp http = new FakeHttp();
            http.responses.put("/messages?", Map.of(
                    "messages", List.of(Map.of("id", "m1"), Map.of("id", "m2"))));
            http.responses.put("/messages/m1", gmailMessage("m1", "Alert 1", "Rs.100 debited"));
            http.responses.put("/messages/m2", gmailMessage("m2", "Alert 2", "Rs.200 debited"));
            http.responses.put("/profile", Map.of("historyId", "5000"));

            FetchResult result = new GmailFetcher(http, BASE)
                    .fetch("token", null, Instant.now().minusSeconds(86400), 50);

            assertThat(result.messages()).hasSize(2);
            assertThat(result.messages().get(0).body()).isEqualTo("Rs.100 debited");
            assertThat(result.nextCursor()).isEqualTo("5000");
            assertThat(result.hasMore()).isFalse();
        }

        @Test
        @DisplayName("takes the cursor from the profile, not from the search")
        void cursorComesFromProfile() {
            FakeHttp http = new FakeHttp();
            http.responses.put("/messages?", Map.of("messages", List.of()));
            http.responses.put("/profile", Map.of("historyId", "9999"));

            FetchResult result = new GmailFetcher(http, BASE)
                    .fetch("token", null, Instant.now().minusSeconds(86400), 50);

            // Anything else would either re-read the mailbox next time or, far
            // worse, skip whatever arrived during this run.
            assertThat(result.nextCursor()).isEqualTo("9999");
            assertThat(http.requested).anyMatch(url -> url.contains("/profile"));
        }

        @Test
        @DisplayName("says so when the provider had more than the budget allowed")
        void reportsMore() {
            FakeHttp http = new FakeHttp();
            http.responses.put("/messages?", Map.of(
                    "messages", List.of(Map.of("id", "m1")),
                    "nextPageToken", "page2"));
            http.responses.put("/messages/m1", gmailMessage("m1", "Alert", "Rs.100 debited"));
            http.responses.put("/profile", Map.of("historyId", "5000"));

            FetchResult result = new GmailFetcher(http, BASE)
                    .fetch("token", null, Instant.now().minusSeconds(86400), 50);

            assertThat(result.hasMore()).isTrue();
        }

        @Test
        @DisplayName("never fetches more than the budget")
        void respectsBudget() {
            FakeHttp http = new FakeHttp();
            List<Map<String, Object>> ids = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ids.add(Map.of("id", "m" + i));
                http.responses.put("/messages/m" + i, gmailMessage("m" + i, "Alert", "Rs.1 debited"));
            }
            http.responses.put("/messages?", Map.of("messages", ids));
            http.responses.put("/profile", Map.of("historyId", "1"));

            FetchResult result = new GmailFetcher(http, BASE)
                    .fetch("token", null, Instant.now().minusSeconds(86400), 3);

            assertThat(result.messages()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("An incremental sync")
    class Incremental {

        @Test
        @DisplayName("asks the history feed from the stored cursor")
        void usesHistory() {
            FakeHttp http = new FakeHttp();
            http.responses.put("/history", Map.of(
                    "historyId", "5100",
                    "history", List.of(Map.of(
                            "id", "5050",
                            "messagesAdded", List.of(Map.of("message", Map.of("id", "m9")))))));
            http.responses.put("/messages/m9", gmailMessage("m9", "Alert", "Rs.300 debited"));

            FetchResult result = new GmailFetcher(http, BASE)
                    .fetch("token", "5000", null, 50);

            assertThat(http.requested.get(0)).contains("startHistoryId=5000");
            assertThat(result.messages()).hasSize(1);
            assertThat(result.nextCursor()).isEqualTo("5100");
            assertThat(result.cursorReset()).isFalse();
        }

        @Test
        @DisplayName("advances the cursor even when nothing happened")
        void advancesOnEmpty() {
            FakeHttp http = new FakeHttp();
            http.responses.put("/history", Map.of("historyId", "5100"));

            FetchResult result = new GmailFetcher(http, BASE).fetch("token", "5000", null, 50);

            // Standing still would mean asking the same question forever.
            assertThat(result.messages()).isEmpty();
            assertThat(result.nextCursor()).isEqualTo("5100");
        }

        @Test
        @DisplayName("counts a message once even if Gmail reports it in two history records")
        void deduplicatesIds() {
            FakeHttp http = new FakeHttp();
            http.responses.put("/history", Map.of(
                    "historyId", "5100",
                    "history", List.of(
                            Map.of("messagesAdded", List.of(Map.of("message", Map.of("id", "m1")))),
                            Map.of("messagesAdded", List.of(Map.of("message", Map.of("id", "m1")))))));
            http.responses.put("/messages/m1", gmailMessage("m1", "Alert", "Rs.100 debited"));

            FetchResult result = new GmailFetcher(http, BASE).fetch("token", "5000", null, 50);

            assertThat(result.messages()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("When Gmail has forgotten the cursor")
    class CursorLost {

        @Test
        @DisplayName("falls back to searching by date rather than giving up")
        void fallsBack() {
            FakeHttp http = new FakeHttp();
            http.cursorLostFor.add("/history");
            http.responses.put("/messages?", Map.of("messages", List.of(Map.of("id", "m1"))));
            http.responses.put("/messages/m1", gmailMessage("m1", "Alert", "Rs.100 debited"));
            http.responses.put("/profile", Map.of("historyId", "7000"));

            FetchResult result = new GmailFetcher(http, BASE)
                    .fetch("token", "1", Instant.now().minusSeconds(86400), 50);

            assertThat(result.messages()).hasSize(1);
            assertThat(result.nextCursor()).isEqualTo("7000");
            assertThat(result.cursorReset()).isTrue();
        }

        @Test
        @DisplayName("still fails loudly when it is the search that is broken")
        void searchFailureIsNotSwallowed() {
            FakeHttp http = new FakeHttp() {
                @Override
                public Map<String, Object> get(String url, String accessToken) {
                    if (url.contains("/messages?")) {
                        throw new MailFetchException("provider is down");
                    }
                    return super.get(url, accessToken);
                }
            };
            http.cursorLostFor.add("/history");

            assertThatThrownBy(() -> new GmailFetcher(http, BASE)
                    .fetch("token", "1", Instant.now().minusSeconds(86400), 50))
                    .isInstanceOf(MailFetchException.class);
        }
    }
}
