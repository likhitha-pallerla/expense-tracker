package com.expensetracker.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Reading Outlook through Microsoft Graph")
class GraphFetcherTest {

    private static final String BASE = "https://graph.test";

    /** Serves a scripted sequence of pages, one per request. */
    private static class ScriptedHttp implements MailHttp {
        final List<String> requested = new ArrayList<>();
        final List<Object> pages = new ArrayList<>();
        int index = 0;

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> get(String url, String accessToken) {
            requested.add(url);
            if (index >= pages.size()) {
                throw new MailFetchException("asked for more pages than the test scripted");
            }
            Object next = pages.get(index++);
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return (Map<String, Object>) next;
        }
    }

    private static Map<String, Object> graphMessage(String id, String subject, String body) {
        return graphMessage(id, subject, body, "text");
    }

    private static Map<String, Object> graphMessage(String id, String subject, String body, String type) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("subject", subject);
        message.put("bodyPreview", "preview");
        message.put("receivedDateTime", "2024-05-12T09:30:00Z");
        message.put("from", Map.of("emailAddress", Map.of(
                "address", "alerts@bank.example", "name", "Bank")));
        message.put("body", Map.of("contentType", type, "content", body));
        return message;
    }

    @Nested
    @DisplayName("Reading one message")
    class OneMessage {

        @Test
        @DisplayName("takes the sender out of Graph's nesting")
        void sender() {
            MailMessage message = GraphFetcher.toMessage(graphMessage("m1", "Alert", "Rs.450 debited"));
            assertThat(message.sender()).isEqualTo("alerts@bank.example");
        }

        @Test
        @DisplayName("survives a message with no sender at all")
        void noSender() {
            Map<String, Object> raw = new LinkedHashMap<>(graphMessage("m1", "Alert", "body"));
            raw.remove("from");

            MailMessage message = GraphFetcher.toMessage(raw);
            assertThat(message).isNotNull();
            assertThat(message.sender()).isNull();
        }

        @Test
        @DisplayName("reduces an HTML body to words")
        void htmlBody() {
            MailMessage message = GraphFetcher.toMessage(
                    graphMessage("m1", "Alert", "<div>Rs.450 <b>debited</b></div>", "html"));

            assertThat(message.body()).isEqualTo("Rs.450 debited");
        }

        @Test
        @DisplayName("reduces HTML that Graph mislabelled as text")
        void mislabelledHtml() {
            MailMessage message = GraphFetcher.toMessage(
                    graphMessage("m1", "Alert", "<div>Rs.450 debited</div>", "text"));

            // Storing markup would poison the fingerprint and hand the parser a
            // template to read.
            assertThat(message.body()).doesNotContain("<div>").contains("Rs.450 debited");
        }

        @Test
        @DisplayName("leaves genuine plain text alone")
        void plainText() {
            MailMessage message = GraphFetcher.toMessage(
                    graphMessage("m1", "Alert", "Rs.450 debited at 5 < 10 rate", "text"));

            assertThat(message.body()).isEqualTo("Rs.450 debited at 5 < 10 rate");
        }

        @Test
        @DisplayName("reads the date")
        void date() {
            MailMessage message = GraphFetcher.toMessage(graphMessage("m1", "Alert", "body"));
            assertThat(message.receivedAt()).isEqualTo(Instant.parse("2024-05-12T09:30:00Z"));
        }

        @Test
        @DisplayName("keeps a message whose date is unreadable")
        void badDate() {
            Map<String, Object> raw = new LinkedHashMap<>(graphMessage("m1", "Alert", "body"));
            raw.put("receivedDateTime", "sometime on Tuesday");

            MailMessage message = GraphFetcher.toMessage(raw);
            assertThat(message).isNotNull();
            assertThat(message.receivedAt()).isNull();
        }

        @Test
        @DisplayName("refuses a message with no id")
        void noId() {
            assertThat(GraphFetcher.toMessage(Map.of("subject", "x"))).isNull();
            assertThat(GraphFetcher.toMessage(null)).isNull();
        }
    }

    @Nested
    @DisplayName("A first sync")
    class Backfill {

        @Test
        @DisplayName("asks the inbox delta feed, bounded by date")
        void firstRequest() {
            ScriptedHttp http = new ScriptedHttp();
            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m1", "Alert", "Rs.450 debited")),
                    "@odata.deltaLink", BASE + "/delta?token=abc"));

            FetchResult result = new GraphFetcher(http, BASE)
                    .fetch("token", null, Instant.parse("2024-01-01T00:00:00Z"), 50);

            assertThat(http.requested.get(0))
                    .contains("/v1.0/me/mailFolders/inbox/messages/delta")
                    .contains("receivedDateTime")
                    .contains("2024-01-01T00:00:00Z");
            assertThat(result.messages()).hasSize(1);
            assertThat(result.nextCursor()).isEqualTo(BASE + "/delta?token=abc");
        }

        @Test
        @DisplayName("follows next links until the delta link arrives")
        void pagesThrough() {
            ScriptedHttp http = new ScriptedHttp();
            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m1", "A", "Rs.1 debited")),
                    "@odata.nextLink", BASE + "/page2"));
            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m2", "B", "Rs.2 debited")),
                    "@odata.nextLink", BASE + "/page3"));
            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m3", "C", "Rs.3 debited")),
                    "@odata.deltaLink", BASE + "/delta?token=done"));

            FetchResult result = new GraphFetcher(http, BASE).fetch("token", null, null, 50);

            assertThat(result.messages()).hasSize(3);
            assertThat(http.requested).hasSize(3);
            assertThat(result.nextCursor()).isEqualTo(BASE + "/delta?token=done");
            assertThat(result.hasMore()).isFalse();
        }

        @Test
        @DisplayName("stops on budget and stores the next link so the pass resumes")
        void stopsOnBudget() {
            ScriptedHttp http = new ScriptedHttp();
            http.pages.add(Map.of(
                    "value", List.of(
                            graphMessage("m1", "A", "Rs.1 debited"),
                            graphMessage("m2", "B", "Rs.2 debited")),
                    "@odata.nextLink", BASE + "/page2"));

            FetchResult result = new GraphFetcher(http, BASE).fetch("token", null, null, 2);

            assertThat(result.messages()).hasSize(2);
            assertThat(result.hasMore()).isTrue();
            // Not the delta link: continuing the same pass is the whole point.
            assertThat(result.nextCursor()).isEqualTo(BASE + "/page2");
        }
    }

    @Nested
    @DisplayName("An incremental sync")
    class Incremental {

        @Test
        @DisplayName("uses the stored delta link exactly as given")
        void usesDeltaLink() {
            ScriptedHttp http = new ScriptedHttp();
            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m1", "Alert", "Rs.450 debited")),
                    "@odata.deltaLink", BASE + "/delta?token=next"));

            new GraphFetcher(http, BASE).fetch("token", BASE + "/delta?token=stored", null, 50);

            assertThat(http.requested.get(0)).isEqualTo(BASE + "/delta?token=stored");
        }

        @Test
        @DisplayName("ignores deletions rather than acting on them")
        void ignoresRemovals() {
            ScriptedHttp http = new ScriptedHttp();
            Map<String, Object> removed = new LinkedHashMap<>();
            removed.put("id", "m2");
            removed.put("@removed", Map.of("reason", "deleted"));

            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m1", "Alert", "Rs.450 debited"), removed),
                    "@odata.deltaLink", BASE + "/delta?token=next"));

            FetchResult result = new GraphFetcher(http, BASE)
                    .fetch("token", BASE + "/delta?token=stored", null, 50);

            // Deleting the mail does not un-spend the money.
            assertThat(result.messages()).hasSize(1);
            assertThat(result.messages().get(0).providerMessageId()).isEqualTo("m1");
        }
    }

    @Nested
    @DisplayName("When Microsoft has expired the delta link")
    class CursorLost {

        @Test
        @DisplayName("starts the folder again instead of giving up")
        void restarts() {
            ScriptedHttp http = new ScriptedHttp();
            http.pages.add(new MailCursorLostException("410"));
            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m1", "Alert", "Rs.450 debited")),
                    "@odata.deltaLink", BASE + "/delta?token=fresh"));

            FetchResult result = new GraphFetcher(http, BASE)
                    .fetch("token", BASE + "/delta?token=expired", null, 50);

            assertThat(result.cursorReset()).isTrue();
            assertThat(result.messages()).hasSize(1);
            assertThat(result.nextCursor()).isEqualTo(BASE + "/delta?token=fresh");
            assertThat(http.requested.get(1)).contains("/mailFolders/inbox/messages/delta");
        }

        @Test
        @DisplayName("discards the half-read pass so nothing is counted twice")
        void discardsPartialResults() {
            ScriptedHttp http = new ScriptedHttp();
            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m1", "A", "Rs.1 debited")),
                    "@odata.nextLink", BASE + "/page2"));
            http.pages.add(new MailCursorLostException("410"));
            http.pages.add(Map.of(
                    "value", List.of(graphMessage("m1", "A", "Rs.1 debited")),
                    "@odata.deltaLink", BASE + "/delta?token=fresh"));

            FetchResult result = new GraphFetcher(http, BASE).fetch("token", null, null, 50);

            assertThat(result.messages()).hasSize(1);
        }

        @Test
        @DisplayName("gives up rather than looping when the fresh request is rejected too")
        void doesNotLoop() {
            ScriptedHttp http = new ScriptedHttp();
            http.pages.add(new MailCursorLostException("410"));
            http.pages.add(new MailCursorLostException("410"));

            assertThatThrownBy(() -> new GraphFetcher(http, BASE)
                    .fetch("token", BASE + "/delta?token=expired", null, 50))
                    .isInstanceOf(MailFetchException.class)
                    .hasMessageContaining("fresh delta request");
        }
    }

    @Nested
    @DisplayName("When Graph answers with neither link")
    class Malformed {

        @Test
        @DisplayName("fails rather than silently losing the resume point")
        void noLinks() {
            ScriptedHttp http = new ScriptedHttp();
            http.pages.add(Map.of("value", List.of(graphMessage("m1", "A", "Rs.1 debited"))));

            assertThatThrownBy(() -> new GraphFetcher(http, BASE).fetch("token", null, null, 50))
                    .isInstanceOf(MailFetchException.class);
        }
    }
}
