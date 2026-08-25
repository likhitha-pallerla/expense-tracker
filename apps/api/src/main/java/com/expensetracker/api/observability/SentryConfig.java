package com.expensetracker.api.observability;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.expensetracker.api.ai.Redactor;

import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;

/**
 * Error reporting, on the assumption that everything this application touches
 * is private.
 *
 * <p>Sentry's defaults are written for an average web application, and they are
 * wrong for this one. Out of the box it will attach request bodies, query
 * strings, cookies and headers to an event, and the bodies here are bank alerts
 * and transaction amounts. An error report is not a good enough reason to copy
 * somebody's spending history onto a third party's servers.
 *
 * <p>So every event is stripped down to the part that is actually diagnostic:
 * the stack trace, the route, and the request id that ties it to a log line.
 * The user is identified by their id and nothing else -- no email, no address.
 *
 * <p>Inert without a DSN. Nothing is sent, no connection is opened, and the
 * application starts and runs exactly as it does now, which is what a fork or a
 * local checkout gets.
 */
@Configuration
public class SentryConfig {

    private static final Logger log = LoggerFactory.getLogger(SentryConfig.class);

    /**
     * Headers that carry credentials, or that identify the person rather than
     * the problem. Removed by name rather than by pattern, because a header
     * this list does not know about is removed anyway -- see {@link #scrub}.
     */
    private static final Set<String> KEEP_HEADERS = Set.of(
            "content-type", "user-agent", "x-request-id");

    @Bean
    public SentryOptions.BeforeSendCallback scrubEverythingPrivate() {
        return (event, hint) -> {
            scrub(event);
            return event;
        };
    }

    /**
     * Strips an event down to what helps someone fix the bug.
     *
     * <p>Package-private and separate from the callback so it can be tested
     * without standing up the SDK.
     */
    static void scrub(SentryEvent event) {
        Request request = event.getRequest();
        if (request != null) {
            // The body of a request here is a transaction, a bank alert, or a
            // natural-language sentence about someone's spending. None of it is
            // needed to fix a stack trace.
            request.setData(null);
            request.setQueryString(null);
            request.setCookies(null);
            request.setEnvs(null);

            // Allow-list rather than deny-list. A deny-list is wrong the moment
            // a new header appears, and the cost of being wrong is a leaked
            // Authorization value.
            var headers = request.getHeaders();
            if (headers != null) {
                headers.keySet().removeIf(name -> !KEEP_HEADERS.contains(name.toLowerCase()));
            }
        }

        // A user is an id. Sentry will happily take an email and an IP address,
        // and neither tells you anything a stack trace does not.
        String userId = MDC.get(RequestIdFilter.USER_ID);
        User user = new User();
        if (userId != null) {
            user.setId(userId);
        }
        event.setUser(user);

        // The join between an event here and a line in the server log.
        String requestId = MDC.get(RequestIdFilter.REQUEST_ID);
        if (requestId != null) {
            event.setTag("request_id", requestId);
        }

        // Exception messages are the last place private data hides: a
        // constraint violation quotes the row that broke it, and a parse
        // failure quotes the message it could not read. The same redactor the
        // AI layer uses is applied here, so card numbers and account numbers
        // are masked by rules that are already tested.
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException exception : exceptions) {
                if (exception.getValue() != null) {
                    exception.setValue(Redactor.scrub(exception.getValue()));
                }
            }
        }

        if (event.getMessage() != null && event.getMessage().getFormatted() != null) {
            event.getMessage().setFormatted(
                    Redactor.scrub(event.getMessage().getFormatted()));
        }
    }

    /**
     * Says once, at startup, whether errors are being reported anywhere.
     *
     * <p>Worth a line in the log: silence from an error tracker is ambiguous,
     * and "we had no errors" and "we were never sending them" look identical
     * from the outside.
     */
    @Bean
    public SentryStartupNotice sentryStartupNotice(@Value("${sentry.dsn:}") String dsn) {
        if (dsn == null || dsn.isBlank()) {
            log.info("Sentry has no DSN; errors are logged locally only.");
        } else {
            log.info("Sentry is reporting errors, with request bodies and headers stripped.");
        }
        return new SentryStartupNotice(Sentry.isEnabled());
    }

    /** Exists so the notice above runs at startup rather than on first error. */
    public record SentryStartupNotice(boolean enabled) {
    }
}
