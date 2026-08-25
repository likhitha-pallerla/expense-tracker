package com.expensetracker.api.observability;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One line per request: who, what, the outcome, and how long it took.
 *
 * <p>An interceptor rather than a filter so the user is already known, and so
 * the path is the templated one where Spring provides it — {@code
 * /api/transactions/{id}} rather than a distinct line per transaction, which
 * would make the log unaggregatable and leak which ids exist.
 *
 * <p>Query strings are dropped entirely. They carry filters — date ranges,
 * category ids, search terms — and a search term in an expense tracker is
 * frequently a merchant name. There is no version of that worth keeping.
 */
@Component
public class AccessLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger("access");

    private static final String START_NANOS = "accessLogStart";

    /** Noise. The platform polls health every few seconds, forever. */
    private static final Set<String> QUIET = Set.of(
            "/actuator/health", "/actuator/info", "/api/health");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_NANOS, System.nanoTime());
        tagCurrentUser();
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {

        String path = request.getRequestURI();
        if (QUIET.contains(path)) {
            return;
        }

        Object start = request.getAttribute(START_NANOS);
        long millis = start instanceof Long began ? (System.nanoTime() - began) / 1_000_000 : -1;

        // Tagged again here: preHandle runs before the controller, but a
        // request rejected by the rate limiter never reaches this class's
        // preHandle at all if the limiter ran first, and a failed request may
        // have had its context established later.
        tagCurrentUser();

        int status = response.getStatus();
        if (ex != null || status >= 500) {
            log.error("{} {} -> {} in {}ms", request.getMethod(), path, status, millis, ex);
        } else if (status >= 400) {
            log.warn("{} {} -> {} in {}ms", request.getMethod(), path, status, millis);
        } else {
            log.info("{} {} -> {} in {}ms", request.getMethod(), path, status, millis);
        }
    }

    /** Puts the current user on the log line, if the request had one. */
    private static void tagCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            String subject = token.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                MDC.put(RequestIdFilter.USER_ID, subject);
            }
        }
    }
}
