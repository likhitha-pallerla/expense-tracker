package com.expensetracker.api.ratelimit;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Holds one bucket per user per cost class, and decides.
 *
 * <p><strong>In memory, and therefore per instance.</strong> With two API
 * instances behind a load balancer, a user gets two allowances. That is stated
 * plainly rather than hidden because it is a real limitation, and because it
 * does not matter yet: this deploys as a single free-tier instance, which is
 * also the only instance that needs protecting. Moving to Redis is the fix when
 * there is a second instance, not before — a shared store on the hot path of
 * every request is a new dependency and a new failure mode, bought to solve a
 * problem nobody has.
 *
 * <p>What it does protect against is real: a mobile client stuck in a retry
 * loop, a script iterating over the API, or one enthusiastic account making the
 * service unusable for everyone else sharing the same small machine.
 */
@Component
public class RateLimiter {

    /**
     * A ceiling on how many buckets are kept, so a hostile caller cannot turn
     * the limiter into a memory leak by varying the key. Reaching it triggers a
     * sweep of buckets nobody has touched in a while; if that frees nothing the
     * request is allowed through, because refusing service to defend a counter
     * would be the wrong trade.
     */
    private static final int MAX_BUCKETS = 20_000;

    /** Buckets untouched for this long are of no further use. */
    private static final Duration IDLE_BEFORE_EVICTION = Duration.ofHours(2);

    private final RateLimitProperties properties;
    private final LongSupplier nanoClock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepNanos = new AtomicLong();

    @Autowired
    public RateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    /** Test seam: lets a test move time instead of sleeping through it. */
    RateLimiter(RateLimitProperties properties, LongSupplier nanoClock) {
        this.properties = properties;
        this.nanoClock = nanoClock;
        this.lastSweepNanos.set(nanoClock.getAsLong());
    }

    /**
     * Decides whether one request may proceed.
     *
     * @param identity who is asking: a user id, or an IP address if unknown
     * @param cost     what the request will cost the server
     */
    public TokenBucket.Decision check(String identity, RequestCost cost) {
        if (!properties.enabled()) {
            return TokenBucket.Decision.allowed(Integer.MAX_VALUE);
        }

        long now = nanoClock.getAsLong();
        sweepIfCrowded(now);

        RateLimitProperties.Allowance allowance = properties.allowanceFor(cost);
        TokenBucket bucket = buckets.computeIfAbsent(
                identity + "|" + cost,
                key -> new TokenBucket(allowance.permits(), allowance.window(), now));

        // Buckets are not thread-safe, and two requests from the same user can
        // arrive together. Locking the individual bucket rather than the map
        // keeps unrelated users out of each other's way.
        synchronized (bucket) {
            return bucket.tryConsume(now);
        }
    }

    /** How many buckets are currently held. Exposed for tests and diagnostics. */
    public int size() {
        return buckets.size();
    }

    private void sweepIfCrowded(long now) {
        if (buckets.size() < MAX_BUCKETS) {
            return;
        }

        // One sweep at a time. Under a flood every request would otherwise try
        // to sweep at once, and the defence would cost more than the attack.
        long lastSweep = lastSweepNanos.get();
        if (!lastSweepNanos.compareAndSet(lastSweep, now)) {
            return;
        }

        long cutoff = now - IDLE_BEFORE_EVICTION.toNanos();
        buckets.values().removeIf(bucket -> {
            synchronized (bucket) {
                return bucket.lastUsedNanos() < cutoff;
            }
        });

        if (buckets.size() < MAX_BUCKETS) {
            return;
        }

        // Still full of buckets in active use. Emptying the map would be the
        // wrong move: it hands a fresh allowance to everyone, including
        // whoever caused the crowding, so flooding the limiter would become a
        // way to defeat it. Drop the least recently used instead — the busiest
        // callers are the ones whose counts are worth keeping, and they are
        // exactly the ones an idle-time ordering keeps.
        buckets.entrySet().stream()
                .sorted((a, b) -> Long.compare(lastUsed(a.getValue()), lastUsed(b.getValue())))
                .limit(MAX_BUCKETS / 4)
                .map(Map.Entry::getKey)
                .forEach(buckets::remove);
    }

    private static long lastUsed(TokenBucket bucket) {
        synchronized (bucket) {
            return bucket.lastUsedNanos();
        }
    }
}
