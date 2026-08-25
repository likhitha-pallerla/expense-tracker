package com.expensetracker.api.ratelimit;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies the rate limit to every request, before the controller runs.
 *
 * <p>An interceptor rather than a servlet filter, so that Spring Security has
 * already run and the caller's identity is known. Limiting by IP when the user
 * id is sitting in the security context would put everyone behind one office
 * router into the same bucket.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final RateLimiter limiter;

    public RateLimitInterceptor(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        String path = request.getRequestURI();

        // The platform polls this to decide whether the instance is alive.
        // Rate-limiting it would let a busy minute look like an outage and get
        // the service restarted.
        if (path.startsWith("/actuator/health") || path.equals("/api/health")) {
            return true;
        }

        String userId = currentUserId();
        String identity = userId != null ? "user:" + userId : "ip:" + clientIp(request);
        RequestCost cost = userId != null
                ? RequestCost.of(request.getMethod(), path)
                : RequestCost.ANONYMOUS;

        TokenBucket.Decision decision = limiter.check(identity, cost);
        if (decision.allowed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
            return true;
        }

        log.warn("rate limit hit: cost={} path={} identity={}", cost, path, identity);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"about:blank","title":"Too many requests",\
                "status":429,"detail":"%s","retryAfterSeconds":%d}"""
                .formatted(message(cost, decision.retryAfterSeconds()), decision.retryAfterSeconds()));
        return false;
    }

    /**
     * Says which activity was throttled, so the message is actionable. "Too
     * many requests" on a page that only reads is baffling if the real cause
     * was a sync loop in another tab.
     */
    private static String message(RequestCost cost, long seconds) {
        String what = switch (cost) {
            case SYNC -> "Syncing and importing are limited";
            case AI -> "AI features are limited";
            case WRITE -> "Saving changes is limited";
            case READ -> "Reading is limited";
            case ANONYMOUS -> "Requests from this address are limited";
        };
        return what + " to protect the service. Try again in "
                + (seconds < 60 ? seconds + " seconds." : (seconds / 60) + " minutes.");
    }

    private static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            String subject = token.getToken().getSubject();
            return subject == null || subject.isBlank() ? null : subject;
        }
        return null;
    }

    /**
     * The caller's address as far as it can be known.
     *
     * <p>Only the first entry of {@code X-Forwarded-For} is used, and only
     * because this runs behind a proxy that sets it. A client can send that
     * header itself, so it is a hint rather than a fact — which is why the
     * limits that matter are keyed by user id, and this path only covers
     * requests that have no user yet.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
