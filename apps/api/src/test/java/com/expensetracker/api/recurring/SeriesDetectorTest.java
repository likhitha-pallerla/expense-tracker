package com.expensetracker.api.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.expensetracker.api.recurring.SeriesDetector.Charge;
import com.expensetracker.api.recurring.SeriesDetector.Series;

class SeriesDetectorTest {

    private static final BigDecimal PRICE = new BigDecimal("499.00");

    /** A charge every {@code stepDays} starting at {@code start}. */
    private static List<Charge> every(LocalDate start, int stepDays, int count, BigDecimal amount) {
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            charges.add(new Charge(start.plusDays((long) stepDays * i), amount));
        }
        return charges;
    }

    /** A charge on the same day of every month. */
    private static List<Charge> monthly(LocalDate start, int count, BigDecimal amount) {
        List<Charge> charges = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            charges.add(new Charge(start.plusMonths(i), amount));
        }
        return charges;
    }

    @Nested
    @DisplayName("evidence needed before anything is claimed")
    class Evidence {

        @Test
        void nothingAtAllIsNotASeries() {
            assertThat(SeriesDetector.detect(List.of())).isEmpty();
        }

        @Test
        void oneChargeIsNotASeries() {
            assertThat(SeriesDetector.detect(List.of(new Charge(LocalDate.of(2024, 1, 5), PRICE))))
                    .isEmpty();
        }

        @Test
        void twoChargesAreACoincidence() {
            assertThat(SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 2, PRICE)))
                    .isEmpty();
        }

        @Test
        void threeChargesAreAPattern() {
            assertThat(SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 3, PRICE)))
                    .isPresent();
        }

        @Test
        void moreChargesMeanMoreConfidence() {
            double three = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 3, PRICE))
                    .orElseThrow().confidence();
            double eight = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 8, PRICE))
                    .orElseThrow().confidence();
            assertThat(eight).isGreaterThan(three);
        }
    }

    @Nested
    @DisplayName("cadences")
    class Cadences {

        @Test
        void weekly() {
            Series series = SeriesDetector.detect(every(LocalDate.of(2024, 3, 1), 7, 6, PRICE))
                    .orElseThrow();
            assertThat(series.cadence()).isEqualTo(Cadence.WEEKLY);
        }

        @Test
        void fortnightly() {
            Series series = SeriesDetector.detect(every(LocalDate.of(2024, 3, 1), 14, 6, PRICE))
                    .orElseThrow();
            assertThat(series.cadence()).isEqualTo(Cadence.FORTNIGHTLY);
        }

        @Test
        void monthlyAcrossCalendarMonthsOfDifferentLengths() {
            Series series = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 15), 6, PRICE))
                    .orElseThrow();
            assertThat(series.cadence()).isEqualTo(Cadence.MONTHLY);
        }

        @Test
        void quarterly() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2023, 1, 10), PRICE),
                    new Charge(LocalDate.of(2023, 4, 10), PRICE),
                    new Charge(LocalDate.of(2023, 7, 10), PRICE),
                    new Charge(LocalDate.of(2023, 10, 10), PRICE));
            assertThat(SeriesDetector.detect(charges).orElseThrow().cadence())
                    .isEqualTo(Cadence.QUARTERLY);
        }

        @Test
        void yearly() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2021, 6, 1), PRICE),
                    new Charge(LocalDate.of(2022, 6, 1), PRICE),
                    new Charge(LocalDate.of(2023, 6, 1), PRICE),
                    new Charge(LocalDate.of(2024, 6, 1), PRICE));
            assertThat(SeriesDetector.detect(charges).orElseThrow().cadence())
                    .isEqualTo(Cadence.YEARLY);
        }

        @Test
        @DisplayName("a gap between the bands is a habit, not a plan")
        void fortyFiveDayGapsAreNotASubscription() {
            assertThat(SeriesDetector.detect(every(LocalDate.of(2024, 1, 1), 45, 6, PRICE)))
                    .isEmpty();
        }

        @Test
        @DisplayName("a daily charge is a commute, not a subscription")
        void dailyChargesAreNotDetected() {
            assertThat(SeriesDetector.detect(every(LocalDate.of(2024, 1, 1), 1, 20, PRICE)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("charges that are late, missing or doubled")
    class Imperfect {

        @Test
        @DisplayName("a few days of drift is still the same subscription")
        void toleratesDrift() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 5), PRICE),
                    new Charge(LocalDate.of(2024, 2, 7), PRICE),
                    new Charge(LocalDate.of(2024, 3, 4), PRICE),
                    new Charge(LocalDate.of(2024, 4, 6), PRICE));
            assertThat(SeriesDetector.detect(charges).orElseThrow().cadence())
                    .isEqualTo(Cadence.MONTHLY);
        }

        @Test
        @DisplayName("a skipped month is a hole in the series, not the end of it")
        void toleratesOneSkippedCycle() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 5), PRICE),
                    new Charge(LocalDate.of(2024, 2, 5), PRICE),
                    // March missing
                    new Charge(LocalDate.of(2024, 4, 5), PRICE),
                    new Charge(LocalDate.of(2024, 5, 5), PRICE));
            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.cadence()).isEqualTo(Cadence.MONTHLY);
            assertThat(series.occurrences()).isEqualTo(4);
        }

        @Test
        @DisplayName("every gap being two months means it is not monthly")
        void doesNotCallAnEveryOtherMonthChargeMonthly() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 5), PRICE),
                    new Charge(LocalDate.of(2024, 3, 5), PRICE),
                    new Charge(LocalDate.of(2024, 5, 5), PRICE),
                    new Charge(LocalDate.of(2024, 7, 5), PRICE));
            assertThat(SeriesDetector.detect(charges)).isEmpty();
        }

        @Test
        @DisplayName("a year-long hole is two subscriptions, not one")
        void rejectsAGapBeyondThreeCycles() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 5), PRICE),
                    new Charge(LocalDate.of(2024, 2, 5), PRICE),
                    new Charge(LocalDate.of(2025, 6, 5), PRICE));
            assertThat(SeriesDetector.detect(charges)).isEmpty();
        }

        @Test
        @DisplayName("two rows on one day are one charge")
        void collapsesSameDayCharges() {
            List<Charge> charges = new ArrayList<>(monthly(LocalDate.of(2024, 1, 5), 4, PRICE));
            charges.add(new Charge(LocalDate.of(2024, 1, 5), new BigDecimal("90.00")));

            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.occurrences()).isEqualTo(4);
        }

        @Test
        void randomShoppingIsNotASeries() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 1), new BigDecimal("120")),
                    new Charge(LocalDate.of(2024, 1, 4), new BigDecimal("40")),
                    new Charge(LocalDate.of(2024, 1, 18), new BigDecimal("860")),
                    new Charge(LocalDate.of(2024, 2, 15), new BigDecimal("55")),
                    new Charge(LocalDate.of(2024, 2, 16), new BigDecimal("310")));
            assertThat(SeriesDetector.detect(charges)).isEmpty();
        }

        @Test
        void unsortedInputIsSortedFirst() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 3, 5), PRICE),
                    new Charge(LocalDate.of(2024, 1, 5), PRICE),
                    new Charge(LocalDate.of(2024, 4, 5), PRICE),
                    new Charge(LocalDate.of(2024, 2, 5), PRICE));
            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.firstCharge()).isEqualTo(LocalDate.of(2024, 1, 5));
            assertThat(series.lastCharge()).isEqualTo(LocalDate.of(2024, 4, 5));
        }
    }

    @Nested
    @DisplayName("when the next charge is expected")
    class NextCharge {

        @Test
        void oneCadenceAfterTheLastCharge() {
            Series series = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 10), 4, PRICE))
                    .orElseThrow();
            assertThat(series.lastCharge()).isEqualTo(LocalDate.of(2024, 4, 10));
            assertThat(series.nextExpected()).isEqualTo(LocalDate.of(2024, 5, 10));
        }

        @Test
        @DisplayName("a subscription billed on the 31st is not stranded on the 28th")
        void recoversTheAnchorDayAfterAShortMonth() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2023, 12, 31), PRICE),
                    new Charge(LocalDate.of(2024, 1, 31), PRICE),
                    new Charge(LocalDate.of(2024, 2, 29), PRICE));

            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.anchorDay()).isEqualTo(31);
            assertThat(series.nextExpected()).isEqualTo(LocalDate.of(2024, 3, 31));
        }

        @Test
        @DisplayName("a charge that slipped a day has not moved the subscription")
        void anchorDayIsTheCommonDayNotTheLastOne() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 1), PRICE),
                    new Charge(LocalDate.of(2024, 2, 1), PRICE),
                    new Charge(LocalDate.of(2024, 3, 1), PRICE),
                    new Charge(LocalDate.of(2024, 4, 2), PRICE));

            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.anchorDay()).isEqualTo(1);
            assertThat(series.nextExpected()).isEqualTo(LocalDate.of(2024, 5, 1));
        }

        @Test
        void weeklyAdvancesBySevenDays() {
            Series series = SeriesDetector.detect(every(LocalDate.of(2024, 3, 1), 7, 5, PRICE))
                    .orElseThrow();
            assertThat(series.nextExpected()).isEqualTo(series.lastCharge().plusDays(7));
        }

        @Test
        void yearlyLandsOnTheSameDateNextYear() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2021, 6, 1), PRICE),
                    new Charge(LocalDate.of(2022, 6, 1), PRICE),
                    new Charge(LocalDate.of(2023, 6, 1), PRICE));
            assertThat(SeriesDetector.detect(charges).orElseThrow().nextExpected())
                    .isEqualTo(LocalDate.of(2024, 6, 1));
        }
    }

    @Nested
    @DisplayName("amounts")
    class Amounts {

        @Test
        void steadyAmountsDoNotVary() {
            Series series = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 5, PRICE))
                    .orElseThrow();
            assertThat(series.amountVaries()).isFalse();
            assertThat(series.typicalAmount()).isEqualByComparingTo(PRICE);
            assertThat(series.latestAmount()).isEqualByComparingTo(PRICE);
            assertThat(series.priceChanged()).isFalse();
        }

        @Test
        @DisplayName("a utility bill recurs even though it is never the same")
        void variableAmountsAreStillDetected() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 8), new BigDecimal("1840")),
                    new Charge(LocalDate.of(2024, 2, 8), new BigDecimal("2310")),
                    new Charge(LocalDate.of(2024, 3, 8), new BigDecimal("1620")),
                    new Charge(LocalDate.of(2024, 4, 8), new BigDecimal("2950")));

            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.cadence()).isEqualTo(Cadence.MONTHLY);
            assertThat(series.amountVaries()).isTrue();
        }

        @Test
        @DisplayName("a bill that always varies has not had a price rise")
        void variableAmountsAreNotAPriceChange() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 8), new BigDecimal("1840")),
                    new Charge(LocalDate.of(2024, 2, 8), new BigDecimal("2310")),
                    new Charge(LocalDate.of(2024, 3, 8), new BigDecimal("1620")),
                    new Charge(LocalDate.of(2024, 4, 8), new BigDecimal("2950")));
            assertThat(SeriesDetector.detect(charges).orElseThrow().priceChanged()).isFalse();
        }

        @Test
        @DisplayName("a settled price stepping to a new one is a price change")
        void detectsAPriceRise() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 5), PRICE),
                    new Charge(LocalDate.of(2024, 2, 5), PRICE),
                    new Charge(LocalDate.of(2024, 3, 5), PRICE),
                    new Charge(LocalDate.of(2024, 4, 5), new BigDecimal("649.00")));

            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.priceChanged()).isTrue();
            assertThat(series.typicalAmount()).isEqualByComparingTo(PRICE);
            assertThat(series.latestAmount()).isEqualByComparingTo(new BigDecimal("649.00"));
        }

        @Test
        void detectsAPriceCut() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 5), PRICE),
                    new Charge(LocalDate.of(2024, 2, 5), PRICE),
                    new Charge(LocalDate.of(2024, 3, 5), PRICE),
                    new Charge(LocalDate.of(2024, 4, 5), new BigDecimal("199.00")));
            assertThat(SeriesDetector.detect(charges).orElseThrow().priceChanged()).isTrue();
        }

        @Test
        @DisplayName("rounding of a rupee or two is not a price change")
        void ignoresTrivialAmountDifferences() {
            List<Charge> charges = List.of(
                    new Charge(LocalDate.of(2024, 1, 5), new BigDecimal("499.00")),
                    new Charge(LocalDate.of(2024, 2, 5), new BigDecimal("499.00")),
                    new Charge(LocalDate.of(2024, 3, 5), new BigDecimal("499.00")),
                    new Charge(LocalDate.of(2024, 4, 5), new BigDecimal("500.00")));

            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.priceChanged()).isFalse();
            assertThat(series.amountVaries()).isFalse();
        }

        @Test
        void steadyAmountsScoreHigherThanErraticOnes() {
            double steady = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 5, PRICE))
                    .orElseThrow().confidence();

            List<Charge> erratic = List.of(
                    new Charge(LocalDate.of(2024, 1, 5), new BigDecimal("100")),
                    new Charge(LocalDate.of(2024, 2, 5), new BigDecimal("900")),
                    new Charge(LocalDate.of(2024, 3, 5), new BigDecimal("300")),
                    new Charge(LocalDate.of(2024, 4, 5), new BigDecimal("1500")),
                    new Charge(LocalDate.of(2024, 5, 5), new BigDecimal("250")));

            assertThat(steady)
                    .isGreaterThan(SeriesDetector.detect(erratic).orElseThrow().confidence());
        }
    }

    @Nested
    @DisplayName("the reasons shown to the user")
    class Reasons {

        @Test
        void saysHowOftenItWasCharged() {
            Series series = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 6, PRICE))
                    .orElseThrow();
            assertThat(series.reasons()).contains("Charged 6 times");
        }

        @Test
        void saysWhenEveryChargeWasOnTime() {
            Series series = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 5, PRICE))
                    .orElseThrow();
            assertThat(series.reasons()).contains("Every charge arrived monthly");
        }

        @Test
        void saysWhenSomeChargesWereNot() {
            List<Charge> charges = new ArrayList<>(monthly(LocalDate.of(2024, 1, 5), 5, PRICE));
            charges.add(new Charge(LocalDate.of(2024, 5, 22), PRICE));

            Series series = SeriesDetector.detect(charges).orElseThrow();
            assertThat(series.reasons()).anyMatch(r -> r.matches("\\d+ of \\d+ gaps were monthly"));
        }

        @Test
        void namesTheAmountWhenItIsSteady() {
            Series series = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 5, PRICE))
                    .orElseThrow();
            assertThat(series.reasons()).contains("Always 499");
        }

        @Test
        void everySeriesExplainsItself() {
            Series series = SeriesDetector.detect(monthly(LocalDate.of(2024, 1, 5), 4, PRICE))
                    .orElseThrow();
            assertThat(series.reasons()).hasSize(4).noneMatch(String::isBlank);
        }
    }

    @Nested
    @DisplayName("cadence arithmetic")
    class CadenceMath {

        @Test
        void labelsRoundTrip() {
            for (Cadence cadence : Cadence.values()) {
                assertThat(Cadence.from(cadence.label())).contains(cadence);
            }
        }

        @Test
        void unknownLabelIsRejected() {
            assertThat(Cadence.from("daily")).isEmpty();
            assertThat(Cadence.from(null)).isEmpty();
        }

        @Test
        void storedDaysMapToTheNearestCadence() {
            assertThat(Cadence.nearest(7)).isEqualTo(Cadence.WEEKLY);
            assertThat(Cadence.nearest(31)).isEqualTo(Cadence.MONTHLY);
            assertThat(Cadence.nearest(90)).isEqualTo(Cadence.QUARTERLY);
            assertThat(Cadence.nearest(366)).isEqualTo(Cadence.YEARLY);
        }

        @Test
        void aNonsenseGapBelongsToNoCadence() {
            Optional<Integer> cycles = Cadence.MONTHLY.cyclesIn(45);
            assertThat(cycles).isEmpty();
        }

        @Test
        void aZeroOrNegativeGapBelongsToNoCadence() {
            assertThat(Cadence.MONTHLY.cyclesIn(0)).isEmpty();
            assertThat(Cadence.MONTHLY.cyclesIn(-30)).isEmpty();
        }

        @Test
        void aSkippedCycleStillCounts() {
            assertThat(Cadence.MONTHLY.cyclesIn(61)).contains(2);
        }

        @Test
        void fourCyclesIsTooLongAGap() {
            assertThat(Cadence.MONTHLY.cyclesIn(120)).isEmpty();
        }

        @Test
        void advancingAMonthlyCadenceClampsToTheMonthLength() {
            assertThat(Cadence.MONTHLY.advance(LocalDate.of(2024, 1, 31), 31))
                    .isEqualTo(LocalDate.of(2024, 2, 29));
            assertThat(Cadence.MONTHLY.advance(LocalDate.of(2023, 1, 31), 31))
                    .isEqualTo(LocalDate.of(2023, 2, 28));
        }

        @Test
        void advancingNeverWalksBackwardsThroughTheCalendar() {
            LocalDate at = LocalDate.of(2024, 1, 31);
            for (int i = 0; i < 12; i++) {
                LocalDate next = Cadence.MONTHLY.advance(at, 31);
                assertThat(next).isAfter(at);
                at = next;
            }
            assertThat(at).isEqualTo(LocalDate.of(2025, 1, 31));
        }

        @Test
        void weeklyIgnoresTheAnchorDay() {
            assertThat(Cadence.WEEKLY.advance(LocalDate.of(2024, 2, 26), 1))
                    .isEqualTo(LocalDate.of(2024, 3, 4));
        }
    }
}
