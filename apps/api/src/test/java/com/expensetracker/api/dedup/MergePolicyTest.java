package com.expensetracker.api.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MergePolicyTest {

    private static DedupVerdict autoMerge(Map<String, Object> signals) {
        return DedupVerdict.of(0.95, DedupVerdict.Decision.AUTO_MERGE, signals);
    }

    @Nested
    @DisplayName("hand-typed transactions")
    class Manual {

        @Test
        void areNeverMergedAutomatically() {
            DedupVerdict.Decision decision =
                    MergePolicy.decide(autoMerge(Map.of()), "manual", "gmail");

            assertThat(decision).isEqualTo(DedupVerdict.Decision.REVIEW);
        }

        @Test
        void areNotMergedEvenWhenBothSidesAreManual() {
            DedupVerdict.Decision decision =
                    MergePolicy.decide(autoMerge(Map.of()), "manual", "manual");

            assertThat(decision).isEqualTo(DedupVerdict.Decision.REVIEW);
        }

        @Test
        void areStillMergedWhenTheBankReferenceMatches() {
            DedupVerdict verdict = DedupVerdict.of(1.0, DedupVerdict.Decision.AUTO_MERGE,
                    Map.of("externalRef", "equal"));

            assertThat(MergePolicy.decide(verdict, "manual", "manual"))
                    .isEqualTo(DedupVerdict.Decision.AUTO_MERGE);
        }
    }

    @Nested
    @DisplayName("ingested transactions")
    class Ingested {

        @Test
        void areMergedAutomatically() {
            DedupVerdict.Decision decision =
                    MergePolicy.decide(autoMerge(Map.of()), "gmail", "android_sms");

            assertThat(decision).isEqualTo(DedupVerdict.Decision.AUTO_MERGE);
        }

        @Test
        void keepReviewVerdictsAsReview() {
            DedupVerdict verdict =
                    DedupVerdict.of(0.7, DedupVerdict.Decision.REVIEW, Map.of());

            assertThat(MergePolicy.decide(verdict, "gmail", "android_sms"))
                    .isEqualTo(DedupVerdict.Decision.REVIEW);
        }

        @Test
        void keepDistinctVerdictsAsDistinct() {
            DedupVerdict verdict =
                    DedupVerdict.of(0.1, DedupVerdict.Decision.DISTINCT, Map.of());

            assertThat(MergePolicy.decide(verdict, "manual", "gmail"))
                    .isEqualTo(DedupVerdict.Decision.DISTINCT);
        }
    }

    @Nested
    @DisplayName("the repeat-purchase case this policy exists for")
    class RepeatPurchase {

        /**
         * Two coffees bought two minutes apart on the same card score exactly
         * 0.90 and would otherwise be merged, losing one of them.
         */
        @Test
        void twoIdenticalManualPurchasesGoToReviewRatherThanMerging() {
            UUID account = UUID.randomUUID();
            UUID merchant = UUID.randomUUID();
            Instant first = Instant.parse("2025-03-01T09:00:00Z");

            DedupCandidate a = new DedupCandidate(UUID.randomUUID(), new BigDecimal("120.00"),
                    "INR", "debit", first, account, merchant, "STARBUCKS", null, "manual");
            DedupCandidate b = new DedupCandidate(UUID.randomUUID(), new BigDecimal("120.00"),
                    "INR", "debit", first.plusSeconds(120), account, merchant, "STARBUCKS",
                    null, "manual");

            DedupVerdict verdict = DedupEngine.compare(a, b);

            assertThat(verdict.decision()).isEqualTo(DedupVerdict.Decision.AUTO_MERGE);
            assertThat(MergePolicy.decide(verdict, a.sourceProvider(), b.sourceProvider()))
                    .isEqualTo(DedupVerdict.Decision.REVIEW);
        }
    }
}
