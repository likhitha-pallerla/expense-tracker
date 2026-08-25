package com.expensetracker.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;

/**
 * The point of these tests is that an error report must never become a way for
 * someone's bank alerts to leave the building. Sentry's defaults would send
 * them; every assertion here is about taking something away.
 */
class SentryConfigTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private SentryEvent eventWithRequest(Request request) {
        SentryEvent event = new SentryEvent();
        event.setRequest(request);
        return event;
    }

    @Nested
    @DisplayName("request scrubbing")
    class RequestScrubbing {

        @Test
        @DisplayName("drops the request body, which is where the bank alert is")
        void dropsBody() {
            Request request = new Request();
            request.setData("{\"raw\":\"Rs 48500.00 debited from a/c XX4412\"}");

            SentryEvent event = eventWithRequest(request);
            SentryConfig.scrub(event);

            assertThat(event.getRequest().getData()).isNull();
        }

        @Test
        @DisplayName("drops the query string, which carries search terms and date ranges")
        void dropsQueryString() {
            Request request = new Request();
            request.setQueryString("q=psychiatrist&from=2026-01-01");

            SentryEvent event = eventWithRequest(request);
            SentryConfig.scrub(event);

            assertThat(event.getRequest().getQueryString()).isNull();
        }

        @Test
        @DisplayName("drops cookies, which are session tokens")
        void dropsCookies() {
            Request request = new Request();
            request.setCookies("sb-access-token=eyJhbGciOi...");

            SentryEvent event = eventWithRequest(request);
            SentryConfig.scrub(event);

            assertThat(event.getRequest().getCookies()).isNull();
        }

        @Test
        @DisplayName("drops the environment, which holds the database URL and keys")
        void dropsEnvironment() {
            Request request = new Request();
            request.setEnvs(new HashMap<>(Map.of("DATABASE_URL", "postgres://user:pw@host/db")));

            SentryEvent event = eventWithRequest(request);
            SentryConfig.scrub(event);

            assertThat(event.getRequest().getEnvs()).isNull();
        }

        @Test
        @DisplayName("removes the Authorization header")
        void removesAuthorization() {
            Request request = new Request();
            request.setHeaders(new HashMap<>(Map.of(
                    "Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.abc",
                    "Content-Type", "application/json")));

            SentryEvent event = eventWithRequest(request);
            SentryConfig.scrub(event);

            assertThat(event.getRequest().getHeaders()).doesNotContainKey("Authorization");
        }

        @Test
        @DisplayName("removes a header it has never heard of, rather than keeping it")
        void removesUnknownHeader() {
            // The reason this is an allow-list. A deny-list is wrong the first
            // time a proxy or a new client adds something.
            Request request = new Request();
            request.setHeaders(new HashMap<>(Map.of(
                    "X-Some-Future-Token", "secret",
                    "Content-Type", "application/json")));

            SentryEvent event = eventWithRequest(request);
            SentryConfig.scrub(event);

            assertThat(event.getRequest().getHeaders()).doesNotContainKey("X-Some-Future-Token");
        }

        @Test
        @DisplayName("keeps the headers that help read a stack trace")
        void keepsDiagnosticHeaders() {
            Request request = new Request();
            request.setHeaders(new HashMap<>(Map.of(
                    "Content-Type", "application/json",
                    "User-Agent", "Mozilla/5.0")));

            SentryEvent event = eventWithRequest(request);
            SentryConfig.scrub(event);

            assertThat(event.getRequest().getHeaders())
                    .containsKeys("Content-Type", "User-Agent");
        }

        @Test
        @DisplayName("matches header names regardless of case")
        void headerMatchIsCaseInsensitive() {
            // HTTP header names are case-insensitive and clients disagree about
            // casing. A case-sensitive allow-list would drop User-Agent from one
            // client and keep authorization from another.
            Request request = new Request();
            request.setHeaders(new HashMap<>(Map.of(
                    "CONTENT-TYPE", "application/json",
                    "authorization", "Bearer abc")));

            SentryEvent event = eventWithRequest(request);
            SentryConfig.scrub(event);

            assertThat(event.getRequest().getHeaders())
                    .containsKey("CONTENT-TYPE")
                    .doesNotContainKey("authorization");
        }

        @Test
        @DisplayName("survives an event with no request at all")
        void toleratesMissingRequest() {
            // Scheduled jobs and startup failures raise events with no request.
            SentryEvent event = new SentryEvent();

            SentryConfig.scrub(event);

            assertThat(event.getRequest()).isNull();
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("identifies the user by id and nothing else")
        void setsUserId() {
            MDC.put(RequestIdFilter.USER_ID, "8f14e45f-ceea-467a-9575-8b2c1d4a3e21");

            SentryEvent event = new SentryEvent();
            SentryConfig.scrub(event);

            assertThat(event.getUser()).isNotNull();
            assertThat(event.getUser().getId())
                    .isEqualTo("8f14e45f-ceea-467a-9575-8b2c1d4a3e21");
            assertThat(event.getUser().getEmail()).isNull();
            assertThat(event.getUser().getIpAddress()).isNull();
        }

        @Test
        @DisplayName("overwrites an email Sentry attached on its own")
        void overwritesSdkSuppliedEmail() {
            // send-default-pii is false, but this is the belt to that braces:
            // a config change should not silently start sending addresses.
            io.sentry.protocol.User attached = new io.sentry.protocol.User();
            attached.setEmail("someone@example.com");
            attached.setIpAddress("203.0.113.9");

            SentryEvent event = new SentryEvent();
            event.setUser(attached);

            SentryConfig.scrub(event);

            assertThat(event.getUser().getEmail()).isNull();
            assertThat(event.getUser().getIpAddress()).isNull();
        }

        @Test
        @DisplayName("tags the event with the request id so it joins to the log")
        void tagsRequestId() {
            MDC.put(RequestIdFilter.REQUEST_ID, "req-abc123");

            SentryEvent event = new SentryEvent();
            SentryConfig.scrub(event);

            assertThat(event.getTag("request_id")).isEqualTo("req-abc123");
        }

        @Test
        @DisplayName("works outside a request, where there is no id to tag")
        void toleratesMissingRequestId() {
            SentryEvent event = new SentryEvent();

            SentryConfig.scrub(event);

            assertThat(event.getTag("request_id")).isNull();
        }
    }

    @Nested
    @DisplayName("exception and message text")
    class Text {

        @Test
        @DisplayName("masks an account number quoted in an exception message")
        void masksAccountNumberInException() {
            // A constraint violation or a parse failure quotes the input that
            // broke it, and here the input is a bank alert.
            SentryException exception = new SentryException();
            exception.setValue("could not parse: Rs 48500 debited from a/c 123456789012");

            SentryEvent event = new SentryEvent();
            event.setExceptions(List.of(exception));

            SentryConfig.scrub(event);

            assertThat(event.getExceptions().get(0).getValue())
                    .doesNotContain("123456789012");
        }

        @Test
        @DisplayName("masks a card number quoted in a log message")
        void masksCardNumberInMessage() {
            Message message = new Message();
            message.setFormatted("charge failed for card 4111111111111111");

            SentryEvent event = new SentryEvent();
            event.setMessage(message);

            SentryConfig.scrub(event);

            assertThat(event.getMessage().getFormatted())
                    .doesNotContain("4111111111111111");
        }

        @Test
        @DisplayName("leaves the diagnostic part of the message readable")
        void keepsTheActualError() {
            // Scrubbing that removes the error along with the data is not a
            // win; the whole point of the event is to be readable.
            SentryException exception = new SentryException();
            exception.setValue("NullPointerException in ParseService.pending");

            SentryEvent event = new SentryEvent();
            event.setExceptions(List.of(exception));

            SentryConfig.scrub(event);

            assertThat(event.getExceptions().get(0).getValue())
                    .contains("ParseService.pending");
        }

        @Test
        @DisplayName("survives an exception with no message")
        void toleratesNullExceptionValue() {
            SentryException exception = new SentryException();
            exception.setValue(null);

            SentryEvent event = new SentryEvent();
            event.setExceptions(List.of(exception));

            SentryConfig.scrub(event);

            assertThat(event.getExceptions().get(0).getValue()).isNull();
        }
    }
}
