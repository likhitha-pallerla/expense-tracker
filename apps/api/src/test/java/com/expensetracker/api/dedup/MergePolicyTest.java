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

    private static final Provenance TYPED = Provenance.manual();
    private static final Provenance GMAIL = new Provenance("gmail", null);
    private static final Provenance SMS = new Provenance("android_sms", null);

    private static DedupVerdict autoMerge() {
        return DedupVerdict.of(0.95, DedupVerdict.Decision.AUTO_MERGE, Map.of());
    }

    @Nested
    @DisplayName("hand-typed transactions")
    class HandTyped {

        @Test
        void areNeverMergedAutomatically() {
            assertThat(MergePolicy.decide(autoMerge(), TYPED, GMAIL))
                    .isEqualTo(DedupVerdict.Decision.REVIEW);
        }

        @Test
        void areNotMergedEvenWhenBothSidesAreTyped() {
            assertThat(MergePolicy.decide(autoMerge(), TYPED, TYPED))
                    .isEqualTo(DedupVerdict.Decision.REVIEW);
        }

        @Test
        void areStillMergedWhenTheBankReferenceMatches() {
            DedupVerdict verdict = DedupVerdict.of(1.0, DedupVerdict.Decision.AUTO_MERGE,
                    Map.of("externalRef", "equal"));

            assertThat(MergePolicy.decide(verdict, TYPED, TYPED))
                    .isEqualTo(DedupVerdict.Decision.AUTO_MERGE);
        }
    }

    @Nested
    @DisplayName("ingested transactions")
    class Ingested {

        @Test
        void areMergedAutomatically() {
            assertThat(MergePolicy.decide(autoMerge(), GMAIL, SMS))
                    .isEqualTo(DedupVerdict.Decision.AUTO_MERGE);
        }

        @Test
        void keepReviewVerdictsAsReview() {
            DedupVerdict verdict = DedupVerdict.of(0.7, DedupVerdict.Decision.REVIEW, Map.of());

            assertThat(MergePolicy.decide(verdict, GMAIL, SMS))
                    .isEqualTo(DedupVerdict.Decision.REVIEW);
        }

        @Test
        void keepDistinctVerdictsAsDistinct() {
            DedupVerdict verdict = DedupVerdict.of(0.1, DedupVerdict.Decision.DISTINCT, Map.of());

            assertThat(MergePolicy.decide(verdict, TYPED, GMAIL))
                    .isEqualTo(DedupVerdict.Decision.DISTINCT);
        }
    }

    @Nested
    @DisplayName("rows from one uploaded statement")
    class SameImport {

        private final UUID batch = UUID.randomUUID();
        private final Provenance rowA = new Provenance("csv_import", batch);
        private final Provenance rowB = new Provenance("csv_import", batch);

        /**
         * A statement lists each payment once, so two matching lines are two
         * real payments. Merging them would delete money the user spent.
         */
        @Test
        void areNeverMergedWithEachOther() {
            assertThat(MergePolicy.decide(autoMerge(), rowA, rowB))
                    .isEqualTo(DedupVerdict.Decision.DISTINCT);
        }

        /** Nor queued — that would bury the user after every import. */
        @Test
        void areNotEvenQueuedForReview() {
            DedupVerdict verdict = DedupVerdict.of(0.7, DedupVerdict.Decision.REVIEW, Map.of());

            assertThat(MergePolicy.decide(verdict, rowA, rowB))
                    .isEqualTo(DedupVerdict.Decision.DISTINCT);
        }

        @Test
        void areStillCheckedAgainstEarlierImports() {
            Provenance older = new Provenance("csv_import", UUID.randomUUID());

            assertThat(MergePolicy.decide(autoMerge(), rowA, older))
                    .isEqualTo(DedupVerdict.Decision.AUTO_MERGE);
        }

        @Test
        void areStillCheckedAgainstTypedTransactions() {
            assertThat(MergePolicy.decide(autoMerge(), rowA, TYPED))
                    .isEqualTo(DedupVerdict.Decision.REVIEW);
        }

        @Test
        void rowsWithoutABatchAreNotTreatedAsSharingOne() {
            assertThat(MergePolicy.decide(autoMerge(), GMAIL, SMS))
                    .isEqualTo(DedupVerdict.Decision.AUTO_MERGE);
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
        void twoIdenticalTypedPurchasesGoToReviewRatherThanMerging() {
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
            assertThat(MergePolicy.decide(verdict, TYPED, TYPED))
                    .isEqualTo(DedupVerdict.Decision.REVIEW);
        }
    }
}
