package com.expensetracker.api.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.expensetracker.api.dedup.DedupVerdict.Decision;

class DedupEngineTest {

    private static final Instant T0 = Instant.parse("2026-03-14T10:00:00Z");
    private static final UUID ACCOUNT_A = UUID.randomUUID();
    private static final UUID ACCOUNT_B = UUID.randomUUID();

    /** The canonical scenario: one payment reported by both email and SMS. */
    private static DedupCandidate email(String merchant, String amount, Instant at) {
        return new DedupCandidate(UUID.randomUUID(), new BigDecimal(amount), "INR", "debit",
                at, ACCOUNT_A, null, merchant, null, "gmail");
    }

    private static DedupCandidate sms(String merchant, String amount, Instant at) {
        return new DedupCandidate(UUID.randomUUID(), new BigDecimal(amount), "INR", "debit",
                at, ACCOUNT_A, null, merchant, null, "android_sms");
    }

    @Nested
    @DisplayName("bank reference (L1)")
    class ExternalRef {

        @Test
        void equalReferencesMergeEvenWhenEverythingElseDiffers() {
            DedupCandidate a = new DedupCandidate(UUID.randomUUID(), new BigDecimal("100.00"), "INR",
                    "debit", T0, ACCOUNT_A, null, "SWIGGY", "UTR123456", "gmail");
            DedupCandidate b = new DedupCandidate(UUID.randomUUID(), new BigDecimal("999.00"), "USD",
                    "credit", T0.plus(30, ChronoUnit.DAYS), ACCOUNT_B, null, "AMAZON", "utr123456",
                    "android_sms");

            DedupVerdict verdict = DedupEngine.compare(a, b);

            assertThat(verdict.decision()).isEqualTo(Decision.AUTO_MERGE);
            assertThat(verdict.score()).isEqualTo(1.0);
            assertThat(verdict.signals()).containsEntry("externalRef", "equal");
        }

        /**
         * Two different bank references are proof of two different payments.
         * Without this rule, buying the same coffee twice in a morning would be
         * silently merged and the user would lose a real expense.
         */
        @Test
        void differentReferencesAreNeverDuplicates() {
            DedupCandidate a = new DedupCandidate(UUID.randomUUID(), new BigDecimal("200.00"), "INR",
                    "debit", T0, ACCOUNT_A, null, "STARBUCKS", "UTR-1", "gmail");
            DedupCandidate b = new DedupCandidate(UUID.randomUUID(), new BigDecimal("200.00"), "INR",
                    "debit", T0.plusSeconds(30), ACCOUNT_A, null, "STARBUCKS", "UTR-2", "gmail");

            DedupVerdict verdict = DedupEngine.compare(a, b);

            assertThat(verdict.decision()).isEqualTo(Decision.DISTINCT);
            assertThat(verdict.signals()).containsEntry("externalRef", "different");
        }

        @Test
        void oneSidedReferenceFallsThroughToFuzzyScoring() {
            DedupCandidate a = new DedupCandidate(UUID.randomUUID(), new BigDecimal("450.00"), "INR",
                    "debit", T0, ACCOUNT_A, null, "SWIGGY", "UTR-9", "gmail");
            DedupCandidate b = sms("SWIGGY", "450.00", T0.plusSeconds(60));

            assertThat(DedupEngine.compare(a, b).decision()).isEqualTo(Decision.AUTO_MERGE);
        }
    }

    @Nested
    @DisplayName("hard gates")
    class Gates {

        @Test
        void differentAmountsAreNeverDuplicates() {
            DedupVerdict verdict = DedupEngine.compare(
                    email("SWIGGY", "450.00", T0), sms("SWIGGY", "450.50", T0));

            assertThat(verdict.decision()).isEqualTo(Decision.DISTINCT);
            assertThat(verdict.signals()).containsEntry("reject", "amount");
        }

        @Test
        void amountsCompareNumericallyNotTextually() {
            DedupCandidate a = email("SWIGGY", "450.0", T0);
            DedupCandidate b = sms("SWIGGY", "450.00", T0.plusSeconds(45));

            assertThat(DedupEngine.compare(a, b).signals()).containsEntry("amount", "equal");
        }

        @Test
        void aRefundIsNotADuplicateOfTheCharge() {
            DedupCandidate charge = new DedupCandidate(UUID.randomUUID(), new BigDecimal("1200.00"),
                    "INR", "debit", T0, ACCOUNT_A, null, "AMAZON", null, "gmail");
            DedupCandidate refund = new DedupCandidate(UUID.randomUUID(), new BigDecimal("1200.00"),
                    "INR", "credit", T0.plusSeconds(120), ACCOUNT_A, null, "AMAZON", null, "gmail");

            DedupVerdict verdict = DedupEngine.compare(charge, refund);

            assertThat(verdict.decision()).isEqualTo(Decision.DISTINCT);
            assertThat(verdict.signals()).containsEntry("reject", "direction");
        }

        @Test
        void differentCurrenciesAreNeverDuplicates() {
            DedupCandidate inr = email("UBER", "500.00", T0);
            DedupCandidate usd = new DedupCandidate(UUID.randomUUID(), new BigDecimal("500.00"),
                    "USD", "debit", T0, ACCOUNT_A, null, "UBER", null, "android_sms");

            assertThat(DedupEngine.compare(inr, usd).signals()).containsEntry("reject", "currency");
        }

        @Test
        void identicalChargesFarApartAreSeparatePurchases() {
            DedupVerdict verdict = DedupEngine.compare(
                    email("SWIGGY", "450.00", T0),
                    sms("SWIGGY", "450.00", T0.plus(5, ChronoUnit.DAYS)));

            assertThat(verdict.decision()).isEqualTo(Decision.DISTINCT);
            assertThat(verdict.signals()).containsEntry("reject", "timeWindow");
        }

        @Test
        void missingTimestampIsNotTreatedAsAMatch() {
            DedupCandidate noTime = new DedupCandidate(UUID.randomUUID(), new BigDecimal("100.00"),
                    "INR", "debit", null, ACCOUNT_A, null, "SWIGGY", null, "gmail");

            assertThat(DedupEngine.compare(noTime, sms("SWIGGY", "100.00", T0)).decision())
                    .isEqualTo(Decision.DISTINCT);
        }
    }

    @Nested
    @DisplayName("fuzzy scoring (L2)")
    class Fuzzy {

        @Test
        void samePaymentFromEmailAndSmsIsAutoMerged() {
            DedupVerdict verdict = DedupEngine.compare(
                    email("SWIGGY", "450.00", T0), sms("UPI-SWIGGY LTD", "450.00", T0.plusSeconds(90)));

            assertThat(verdict.decision()).isEqualTo(Decision.AUTO_MERGE);
            assertThat(verdict.score()).isGreaterThanOrEqualTo(DedupEngine.AUTO_MERGE_THRESHOLD);
        }

        @Test
        void sameChannelRepeatPurchaseIsNotAutoMerged() {
            // Two ₹200 coffees an hour apart, both from SMS. Plausible repeats,
            // so this must never be merged silently.
            DedupVerdict verdict = DedupEngine.compare(
                    sms("STARBUCKS", "200.00", T0),
                    sms("STARBUCKS", "200.00", T0.plus(1, ChronoUnit.HOURS)));

            assertThat(verdict.decision()).isNotEqualTo(Decision.AUTO_MERGE);
        }

        @Test
        void differentMerchantsSameAmountAndTimeAreNotMerged() {
            DedupVerdict verdict = DedupEngine.compare(
                    email("SWIGGY", "450.00", T0), sms("BIGBASKET", "450.00", T0.plusSeconds(60)));

            assertThat(verdict.decision()).isNotEqualTo(Decision.AUTO_MERGE);
        }

        @Test
        void differentAccountsWeakenTheMatch() {
            DedupCandidate a = email("SWIGGY", "450.00", T0);
            DedupCandidate b = new DedupCandidate(UUID.randomUUID(), new BigDecimal("450.00"), "INR",
                    "debit", T0.plusSeconds(60), ACCOUNT_B, null, "SWIGGY", null, "android_sms");

            DedupVerdict both = DedupEngine.compare(a, b);
            DedupVerdict same = DedupEngine.compare(a, sms("SWIGGY", "450.00", T0.plusSeconds(60)));

            assertThat(both.score()).isLessThan(same.score());
        }

        @Test
        void unknownMerchantOnOneSideStillReachesReview() {
            DedupVerdict verdict = DedupEngine.compare(
                    email("SWIGGY", "450.00", T0), sms(null, "450.00", T0.plusSeconds(60)));

            assertThat(verdict.decision()).isIn(Decision.REVIEW, Decision.AUTO_MERGE);
        }

        @Test
        void sharedMerchantIdBeatsStringComparison() {
            UUID merchant = UUID.randomUUID();
            DedupCandidate a = new DedupCandidate(UUID.randomUUID(), new BigDecimal("450.00"), "INR",
                    "debit", T0, ACCOUNT_A, merchant, "SWIGGY ORDER 8821", null, "gmail");
            DedupCandidate b = new DedupCandidate(UUID.randomUUID(), new BigDecimal("450.00"), "INR",
                    "debit", T0.plusSeconds(60), ACCOUNT_A, merchant, "POS SWG*4471", null,
                    "android_sms");

            assertThat(DedupEngine.compare(a, b).decision()).isEqualTo(Decision.AUTO_MERGE);
        }

        @Test
        void scoreIsSymmetric() {
            DedupCandidate a = email("SWIGGY", "450.00", T0);
            DedupCandidate b = sms("SWIGGY LTD", "450.00", T0.plusSeconds(300));

            assertThat(DedupEngine.compare(a, b).score())
                    .isEqualTo(DedupEngine.compare(b, a).score());
        }

        @Test
        void scoreAlwaysStaysWithinZeroAndOne() {
            for (int minutes : new int[] {0, 1, 10, 45, 120, 500, 1400, 4000}) {
                DedupVerdict verdict = DedupEngine.compare(
                        email("SWIGGY", "450.00", T0),
                        sms("SWIGGY", "450.00", T0.plus(minutes, ChronoUnit.MINUTES)));
                assertThat(verdict.score()).isBetween(0.0, 1.0);
            }
        }

        @Test
        void closerInTimeNeverScoresLower() {
            double previous = Double.MAX_VALUE;
            for (int minutes : new int[] {1, 10, 30, 120, 600, 2000}) {
                double score = DedupEngine.compare(
                        email("SWIGGY", "450.00", T0),
                        sms("SWIGGY", "450.00", T0.plus(minutes, ChronoUnit.MINUTES))).score();
                assertThat(score).isLessThanOrEqualTo(previous);
                previous = score;
            }
        }
    }

    @Test
    void refusesToCompareATransactionWithItself() {
        UUID id = UUID.randomUUID();
        DedupCandidate a = new DedupCandidate(id, new BigDecimal("1.00"), "INR", "debit", T0,
                ACCOUNT_A, null, "X", null, "gmail");

        assertThatThrownBy(() -> DedupEngine.compare(a, a))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
