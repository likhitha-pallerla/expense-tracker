package com.expensetracker.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("rate limiting")
class RateLimiterTest {

    /** A clock the test moves by hand, so nothing has to sleep. */
    private static final class FakeClock {
        private long nanos = 1_000_000_000L;

        long now() {
            return nanos;
        }

        void advance(Duration by) {
            nanos += by.toNanos();
        }
    }

    private static RateLimitProperties props() {
        return new RateLimitProperties(true, 300, 60, 30, 60, 20);
    }

    private static RateLimiter limiterWith(FakeClock clock, RateLimitProperties properties) {
        return new RateLimiter(properties, clock::now);
    }

    @Nested
    @DisplayName("classifying what a request costs")
    class Classifying {

        @Test
        void reading_is_read() {
            assertThat(RequestCost.of("GET", "/api/transactions")).isEqualTo(RequestCost.READ);
        }

        @Test
        void creating_a_record_is_a_write() {
            assertThat(RequestCost.of("POST", "/api/transactions")).isEqualTo(RequestCost.WRITE);
        }

        @Test
        void syncing_a_mailbox_is_expensive() {
            assertThat(RequestCost.of("POST", "/api/sync")).isEqualTo(RequestCost.SYNC);
        }

        @Test
        void so_is_importing_a_statement() {
            assertThat(RequestCost.of("POST", "/api/imports")).isEqualTo(RequestCost.SYNC);
        }

        @Test
        void and_uploading_a_batch_of_messages() {
            assertThat(RequestCost.of("POST", "/api/sms")).isEqualTo(RequestCost.SYNC);
        }

        @Test
        void typing_a_payment_may_reach_a_model() {
            assertThat(RequestCost.of("POST", "/api/entry/parse")).isEqualTo(RequestCost.AI);
        }

        /**
         * The one GET that costs money. Classifying by method first would have
         * charged it the reading rate and let a refresh loop spend the day's
         * budget.
         */
        @Test
        void and_so_may_the_month_summary_even_though_it_is_a_get() {
            assertThat(RequestCost.of("GET", "/api/insights/summary")).isEqualTo(RequestCost.AI);
        }

        @Test
        void listing_connections_is_not_charged_as_a_sync() {
            assertThat(RequestCost.of("GET", "/api/connections")).isEqualTo(RequestCost.READ);
        }

        @Test
        void nor_is_looking_at_the_parse_queue() {
            assertThat(RequestCost.of("GET", "/api/parse/queue")).isEqualTo(RequestCost.READ);
        }

        @Test
        void nor_the_list_of_sync_runs() {
            assertThat(RequestCost.of("GET", "/api/sync/runs")).isEqualTo(RequestCost.READ);
        }

        /**
         * Someone working through fifty unread messages clicks this fifty
         * times. Charging it the sync rate would stop them a third of the way
         * through the job the application asked them to do.
         */
        @Test
        void ignoring_a_message_is_a_cheap_write_despite_its_path() {
            assertThat(RequestCost.of("POST", "/api/parse/abc-123/ignore"))
                    .isEqualTo(RequestCost.WRITE);
        }

        @Test
        void a_missing_path_does_not_throw() {
            assertThat(RequestCost.of("GET", null)).isEqualTo(RequestCost.READ);
        }
    }

    @Nested
    @DisplayName("spending an allowance")
    class Spending {

        @Test
        void the_first_request_is_always_allowed() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, props());
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
        }

        @Test
        void an_allowance_runs_out() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(true, 300, 60, 3, 60, 20));

            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isFalse();
        }

        @Test
        void and_comes_back_over_time() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(true, 300, 60, 3, 60, 20));

            for (int i = 0; i < 3; i++) {
                limiter.check("user:a", RequestCost.SYNC);
            }
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isFalse();

            // Three an hour means one back after twenty minutes.
            clock.advance(Duration.ofMinutes(21));
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isFalse();
        }

        @Test
        void it_never_refills_beyond_the_allowance() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(true, 300, 60, 3, 60, 20));

            limiter.check("user:a", RequestCost.SYNC);
            clock.advance(Duration.ofDays(30));

            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isFalse();
        }

        @Test
        void refusal_says_how_long_to_wait() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(true, 300, 60, 2, 60, 20));

            limiter.check("user:a", RequestCost.SYNC);
            limiter.check("user:a", RequestCost.SYNC);
            TokenBucket.Decision denied = limiter.check("user:a", RequestCost.SYNC);

            assertThat(denied.allowed()).isFalse();
            // Two an hour: half an hour for the next one.
            assertThat(denied.retryAfterSeconds()).isBetween(1750L, 1850L);
        }

        /**
         * Zero would be an invitation to retry immediately, which is the loop
         * the limit exists to break.
         */
        @Test
        void and_never_says_zero_seconds() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(true, 100_000, 60, 30, 60, 20));

            TokenBucket.Decision denied = null;
            for (int i = 0; i < 100_001; i++) {
                denied = limiter.check("user:a", RequestCost.READ);
            }
            assertThat(denied.allowed()).isFalse();
            assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
        }
    }

    @Nested
    @DisplayName("keeping users apart")
    class Isolation {

        @Test
        void one_user_running_out_does_not_affect_another() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(true, 300, 60, 2, 60, 20));

            limiter.check("user:a", RequestCost.SYNC);
            limiter.check("user:a", RequestCost.SYNC);
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isFalse();

            assertThat(limiter.check("user:b", RequestCost.SYNC).allowed()).isTrue();
        }

        /**
         * The whole reason for classifying by cost. Someone who has exhausted
         * their syncs must still be able to open a page and see their data.
         */
        @Test
        void running_out_of_syncs_does_not_stop_them_reading() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(true, 300, 60, 2, 60, 20));

            limiter.check("user:a", RequestCost.SYNC);
            limiter.check("user:a", RequestCost.SYNC);
            assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isFalse();

            assertThat(limiter.check("user:a", RequestCost.READ).allowed()).isTrue();
            assertThat(limiter.check("user:a", RequestCost.WRITE).allowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("switched off")
    class Disabled {

        @Test
        void nothing_is_ever_refused() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(false, 1, 1, 1, 1, 1));

            for (int i = 0; i < 50; i++) {
                assertThat(limiter.check("user:a", RequestCost.SYNC).allowed()).isTrue();
            }
        }

        @Test
        void and_no_memory_is_spent_holding_counts() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(false, 1, 1, 1, 1, 1));

            limiter.check("user:a", RequestCost.SYNC);
            assertThat(limiter.size()).isZero();
        }
    }

    @Nested
    @DisplayName("settings")
    class Settings {

        @Test
        void an_unset_limit_falls_back_to_its_default_rather_than_blocking_everything() {
            RateLimitProperties properties = new RateLimitProperties(true, 0, 0, 0, 0, 0);

            assertThat(properties.readPerMinute()).isEqualTo(300);
            assertThat(properties.writePerMinute()).isEqualTo(60);
            assertThat(properties.syncPerHour()).isEqualTo(30);
            assertThat(properties.aiPerHour()).isEqualTo(60);
            assertThat(properties.anonymousPerMinute()).isEqualTo(20);
        }

        @Test
        void a_negative_limit_is_treated_the_same_way() {
            assertThat(new RateLimitProperties(true, -5, -5, -5, -5, -5).readPerMinute())
                    .isEqualTo(300);
        }

        @Test
        void reads_refill_by_the_minute_and_syncs_by_the_hour() {
            RateLimitProperties properties = props();

            assertThat(properties.allowanceFor(RequestCost.READ).window())
                    .isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.allowanceFor(RequestCost.SYNC).window())
                    .isEqualTo(Duration.ofHours(1));
        }
    }

    @Nested
    @DisplayName("under concurrent use")
    class Concurrency {

        /**
         * Two requests from the same user arrive together often — a page load
         * fans out. A counter read and written without a lock would let more
         * through than the allowance permits, which is the failure this limiter
         * is supposed to prevent.
         */
        @Test
        void the_allowance_is_not_exceeded_when_requests_arrive_together() throws Exception {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, new RateLimitProperties(true, 50, 60, 30, 60, 20));

            int threads = 16;
            int perThread = 20;
            AtomicInteger allowed = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int t = 0; t < threads; t++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < perThread; i++) {
                                if (limiter.check("user:a", RequestCost.READ).allowed()) {
                                    allowed.incrementAndGet();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                start.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            }

            // The clock never moved, so nothing refilled: exactly the
            // allowance should have got through, out of 320 attempts.
            assertThat(allowed.get()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("memory")
    class Memory {

        @Test
        void a_bucket_is_kept_per_user_and_cost() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, props());

            limiter.check("user:a", RequestCost.READ);
            limiter.check("user:a", RequestCost.WRITE);
            limiter.check("user:b", RequestCost.READ);

            assertThat(limiter.size()).isEqualTo(3);
        }

        @Test
        void the_same_user_and_cost_reuses_one_bucket() {
            FakeClock clock = new FakeClock();
            RateLimiter limiter = limiterWith(clock, props());

            for (int i = 0; i < 10; i++) {
                limiter.check("user:a", RequestCost.READ);
            }
            assertThat(limiter.size()).isEqualTo(1);
        }
    }
}
