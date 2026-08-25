package com.expensetracker.api.ratelimit;

import java.time.Duration;

/**
 * A token bucket: an allowance that is spent on request and refills steadily.
 *
 * <p>Chosen over a fixed window because a fixed window has an edge. With "60 a
 * minute" counted per clock minute, 60 requests at 11:59:59 and 60 more at
 * 12:00:00 both pass, and the server sees 120 in one second — precisely the
 * burst the limit existed to prevent. A bucket has no such seam: it refills
 * continuously, so the allowance means the same thing at every instant.
 *
 * <p>It also matches how people actually use the application. Opening the
 * dashboard fires several requests at once, and a bucket that has been idle is
 * full and absorbs that without complaint, while still holding the long-run
 * average to the configured rate. A strict "one every N milliseconds" limiter
 * would reject a normal page load.
 *
 * <p>Not thread-safe on its own; {@link RateLimiter} synchronises on the
 * instance. Time is passed in rather than read from a clock so that the tests
 * can advance it instantly instead of sleeping.
 */
final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;

    private double tokens;
    private long lastRefillNanos;

    /**
     * @param permits     how many requests may be spent before waiting
     * @param window      how long a full allowance takes to refill
     * @param nowNanos    current time, from a monotonic source
     */
    TokenBucket(int permits, Duration window, long nowNanos) {
        this.capacity = Math.max(1, permits);
        this.refillPerNano = this.capacity / (double) Math.max(1, window.toNanos());

        // Starts full. A new user's first request should not be judged against
        // an allowance they have not had time to earn.
        this.tokens = this.capacity;
        this.lastRefillNanos = nowNanos;
    }

    /**
     * Spends one token if there is one.
     *
     * @return an outcome carrying, on refusal, how long until the next token
     */
    Decision tryConsume(long nowNanos) {
        refill(nowNanos);

        if (tokens >= 1.0) {
            tokens -= 1.0;
            return Decision.allowed((int) tokens);
        }

        // How long until the bucket holds a whole token again. Rounded up, and
        // never reported as zero: telling a client to retry in no time at all
        // invites the loop this is here to stop.
        double needed = 1.0 - tokens;
        long waitNanos = (long) Math.ceil(needed / refillPerNano);
        return Decision.denied(Math.max(1, Duration.ofNanos(waitNanos).toSeconds()));
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        lastRefillNanos = nowNanos;
    }

    /** When this bucket was last touched, for evicting ones nobody is using. */
    long lastUsedNanos() {
        return lastRefillNanos;
    }

    /**
     * The answer, and what to tell the client if it is no.
     *
     * @param allowed          whether the request may proceed
     * @param remaining        tokens left, for the response header
     * @param retryAfterSeconds how long until one is available again
     */
    record Decision(boolean allowed, int remaining, long retryAfterSeconds) {

        static Decision allowed(int remaining) {
            return new Decision(true, remaining, 0);
        }

        static Decision denied(long retryAfterSeconds) {
            return new Decision(false, 0, retryAfterSeconds);
        }
    }
}
