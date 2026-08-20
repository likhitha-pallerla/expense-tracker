package com.expensetracker.api.forecast;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProjectionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final ForecastWindow WINDOW = ForecastWindow.of(TODAY, 30);

    private static ExpectedCharge out(String name, int dayOffset, String amount) {
        return charge(name, dayOffset, amount, "debit", true);
    }

    private static ExpectedCharge in(String name, int dayOffset, String amount) {
        return charge(name, dayOffset, amount, "credit", true);
    }

    private static ExpectedCharge charge(
            String name, int dayOffset, String amount, String direction, boolean confirmed) {
        LocalDate on = TODAY.plusDays(dayOffset);
        return new ExpectedCharge(
                UUID.randomUUID(), name, on, dayOffset, new BigDecimal(amount),
                direction, "INR", null, null, "monthly", confirmed, false, false);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    @Nested
    class TheLine {

        @Test
        void aQuietWindowStaysFlat() {
            Projection.Result result = Projection.run(WINDOW, money("5000"), List.of());

            assertThat(result.days()).hasSize(30);
            assertThat(result.closing()).isEqualByComparingTo("5000");
            assertThat(result.days()).allSatisfy(day ->
                    assertThat(day.balance()).isEqualByComparingTo("5000"));
        }

        @Test
        void moneyOutLowersTheLineFromThatDayOn() {
            Projection.Result result =
                    Projection.run(WINDOW, money("5000"), List.of(out("Rent", 5, "2000")));

            assertThat(result.days().get(4).balance()).isEqualByComparingTo("5000");
            assertThat(result.days().get(5).balance()).isEqualByComparingTo("3000");
            assertThat(result.days().get(29).balance()).isEqualByComparingTo("3000");
            assertThat(result.closing()).isEqualByComparingTo("3000");
        }

        @Test
        void moneyInRaisesIt() {
            Projection.Result result =
                    Projection.run(WINDOW, money("1000"), List.of(in("Salary", 3, "50000")));

            assertThat(result.days().get(3).balance()).isEqualByComparingTo("51000");
            assertThat(result.closing()).isEqualByComparingTo("51000");
        }

        @Test
        void severalOnOneDayAreCountedSeparatelyAndSummed() {
            Projection.Result result = Projection.run(WINDOW, money("5000"),
                    List.of(out("Netflix", 2, "500"), out("Spotify", 2, "200")));

            ForecastDay day = result.days().get(2);
            assertThat(day.moneyOut()).isEqualByComparingTo("700");
            assertThat(day.events()).isEqualTo(2);
            assertThat(day.hasEvents()).isTrue();
            assertThat(day.balance()).isEqualByComparingTo("4300");
        }

        @Test
        void aDayWithNothingOnItSaysSo() {
            Projection.Result result =
                    Projection.run(WINDOW, money("5000"), List.of(out("Rent", 5, "2000")));

            assertThat(result.days().get(4).hasEvents()).isFalse();
            assertThat(result.days().get(5).hasEvents()).isTrue();
        }

        @Test
        void chargesOutsideTheWindowAreIgnored() {
            ExpectedCharge late = charge("Insurance", 60, "9000", "debit", true);
            Projection.Result result = Projection.run(WINDOW, money("5000"), List.of(late));

            assertThat(result.closing()).isEqualByComparingTo("5000");
        }
    }

    @Nested
    class Guesses {

        @Test
        void aSuspectedSeriesDoesNotMoveTheLine() {
            ExpectedCharge guess = charge("Maybe Gym", 4, "3000", "debit", false);
            Projection.Result result = Projection.run(WINDOW, money("5000"), List.of(guess));

            // Telling someone they owe money they do not is worse than being
            // quiet about a guess — they might cancel a plan over it.
            assertThat(result.closing()).isEqualByComparingTo("5000");
            assertThat(result.low().balance()).isEqualByComparingTo("5000");
        }
    }

    @Nested
    class TheLowPoint {

        @Test
        void isTheWorstDayNotTheLastOne() {
            // Rent lands before payday: the month ends healthy but the 15th is
            // the day that matters.
            Projection.Result result = Projection.run(WINDOW, money("20000"), List.of(
                    out("Rent", 5, "18000"),
                    in("Salary", 20, "60000")));

            assertThat(result.closing()).isEqualByComparingTo("62000");
            assertThat(result.low().balance()).isEqualByComparingTo("2000");
            assertThat(result.low().date()).isEqualTo(TODAY.plusDays(5));
            assertThat(result.low().daysAway()).isEqualTo(5);
            assertThat(result.low().isAhead()).isTrue();
        }

        @Test
        void reportsTheFirstDayItBottomsOutNotTheLast() {
            Projection.Result result = Projection.run(WINDOW, money("5000"), List.of(
                    out("Rent", 5, "5000"),
                    in("Refund", 25, "100")));

            // Flat at zero from day 5 to day 24; the trouble starts on day 5.
            assertThat(result.low().daysAway()).isEqualTo(5);
        }

        @Test
        void isTodayWhenNothingIsComing() {
            Projection.Result result = Projection.run(WINDOW, money("5000"), List.of());

            assertThat(result.low().daysAway()).isZero();
            assertThat(result.low().date()).isEqualTo(TODAY);
            assertThat(result.low().isAhead()).isFalse();
        }

        @Test
        void admitsWhenTheBalanceGoesNegative() {
            Projection.Result result =
                    Projection.run(WINDOW, money("1000"), List.of(out("Rent", 5, "4000")));

            assertThat(result.low().goesNegative()).isTrue();
            assertThat(result.low().balance()).isEqualByComparingTo("-3000");
            assertThat(result.low().shortfall()).isEqualByComparingTo("3000");
        }

        @Test
        void hasNoShortfallWhenItStaysAboveZero() {
            Projection.Result result =
                    Projection.run(WINDOW, money("5000"), List.of(out("Rent", 5, "2000")));

            assertThat(result.low().goesNegative()).isFalse();
            assertThat(result.low().shortfall()).isEqualByComparingTo("0");
        }

        @Test
        void exactlyZeroIsNotNegative() {
            Projection.Result result =
                    Projection.run(WINDOW, money("2000"), List.of(out("Rent", 5, "2000")));

            assertThat(result.low().balance()).isEqualByComparingTo("0");
            assertThat(result.low().goesNegative()).isFalse();
            assertThat(result.low().shortfall()).isEqualByComparingTo("0");
        }

        @Test
        void anAlreadyOverdrawnAccountIsItsOwnLowPointUntilSomethingWorseHappens() {
            Projection.Result result = Projection.run(WINDOW, money("-500"), List.of());

            assertThat(result.low().balance()).isEqualByComparingTo("-500");
            assertThat(result.low().daysAway()).isZero();
            assertThat(result.low().goesNegative()).isTrue();
        }
    }

    @Nested
    class Signs {

        @Test
        void moneyOutIsNegativeWhenSigned() {
            assertThat(out("Rent", 1, "2000").signedAmount()).isEqualByComparingTo("-2000");
            assertThat(out("Rent", 1, "2000").isIncome()).isFalse();
        }

        @Test
        void moneyInIsPositive() {
            assertThat(in("Salary", 1, "50000").signedAmount()).isEqualByComparingTo("50000");
            assertThat(in("Salary", 1, "50000").isIncome()).isTrue();
        }
    }
}
