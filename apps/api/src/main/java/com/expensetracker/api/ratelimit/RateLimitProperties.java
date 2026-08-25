package com.expensetracker.api.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many requests of each kind one user may make, and how quickly the
 * allowance comes back.
 *
 * <p>The limits differ by what a request <em>costs</em> rather than by its HTTP
 * method, because those are very different things here. Listing transactions is
 * one indexed query. Syncing a mailbox is a round trip to Google, a parse of
 * every new message, and a write per transaction found. Treating both as "a
 * request" would either throttle reading to protect syncing, or leave syncing
 * unprotected to keep reading usable.
 *
 * <p>These are burst limits, not quotas. They exist so that one account — or
 * one buggy client stuck in a retry loop — cannot exhaust a free-tier instance
 * that everyone else is sharing. Someone using the application normally will
 * not meet them: the numbers sit well above what a person generates and well
 * below what a loop does.
 *
 * @param readPerMinute      GETs: generous, since one dashboard fans out
 * @param writePerMinute     creating and editing records by hand
 * @param syncPerHour        mailbox sync, message parsing, statement import
 * @param aiPerHour          anything that may reach a model
 * @param anonymousPerMinute per-IP, for the few endpoints with no user yet
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int readPerMinute,
        int writePerMinute,
        int syncPerHour,
        int aiPerHour,
        int anonymousPerMinute) {

    public RateLimitProperties {
        // Zero would be read as "block everything", which is never what an
        // unset value means. Each falls back to its default instead.
        readPerMinute = readPerMinute <= 0 ? 300 : readPerMinute;
        writePerMinute = writePerMinute <= 0 ? 60 : writePerMinute;
        syncPerHour = syncPerHour <= 0 ? 30 : syncPerHour;
        aiPerHour = aiPerHour <= 0 ? 60 : aiPerHour;
        anonymousPerMinute = anonymousPerMinute <= 0 ? 20 : anonymousPerMinute;
    }

    /** The allowance for a cost class, as a count and the window it refills over. */
    public Allowance allowanceFor(RequestCost cost) {
        return switch (cost) {
            case READ -> new Allowance(readPerMinute, Duration.ofMinutes(1));
            case WRITE -> new Allowance(writePerMinute, Duration.ofMinutes(1));
            case SYNC -> new Allowance(syncPerHour, Duration.ofHours(1));
            case AI -> new Allowance(aiPerHour, Duration.ofHours(1));
            case ANONYMOUS -> new Allowance(anonymousPerMinute, Duration.ofMinutes(1));
        };
    }

    /**
     * A number of requests and the time it takes to earn them all back.
     *
     * @param permits how many may be spent before waiting
     * @param window  how long a full allowance takes to refill
     */
    public record Allowance(int permits, Duration window) {
    }
}
