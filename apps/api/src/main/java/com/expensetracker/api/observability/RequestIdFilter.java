package com.expensetracker.api.observability;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gives every request an id, and puts it on every log line it produces.
 *
 * <p>Without this, a report of "it failed when I clicked sync" is untraceable:
 * the log holds interleaved lines from every concurrent request with nothing
 * tying them together. With it, one id recovers the whole story.
 *
 * <p>The id is returned in {@code X-Request-Id} so a user can quote it, and
 * accepted on the way in so a trace started by the web app carries through to
 * the API. An inbound id is sanitised rather than trusted — it ends up in log
 * lines, and a caller who can put newlines in a log line can forge log entries.
 *
 * <p><strong>What is deliberately not logged:</strong> no amounts, no merchant
 * names, no message bodies, no email addresses, no tokens. The user id is a
 * random UUID with no meaning outside the database, and it is what makes a
 * support request answerable. A log that records what someone spent is a second
 * copy of their financial history, kept somewhere with weaker access control
 * than the database itself.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Public because the exception handler quotes it back to the caller. */
    public static final String REQUEST_ID = "requestId";
    static final String USER_ID = "userId";

    /** Long enough to be unique, short enough to read down a phone line. */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String requestId = sanitise(request.getHeader(HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            // Cleared on the way out because the thread goes back to a pool.
            // Leaving these set would stamp the next request with the last
            // one's user, which is worse than having no id at all.
            MDC.remove(USER_ID);
            MDC.remove(REQUEST_ID);
        }
    }

    /**
     * Strips anything that is not a plain identifier character.
     *
     * <p>A caller controls this header. Allowing a newline through would let
     * them write their own lines into the log, which is how an incident review
     * ends up chasing an event that never happened.
     */
    static String sanitise(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.strip().replaceAll("[^A-Za-z0-9._:-]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
