package com.expensetracker.api.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.expensetracker.api.budgets.BudgetView;
import com.expensetracker.api.cards.CardView;
import com.expensetracker.api.notifications.AlertBuilder.Inputs;
import com.expensetracker.api.recurring.RecurringView;

class AlertBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 3, 20);
    private static final UUID BUDGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CARD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static BigDecimal money(long value) {
        return BigDecimal.valueOf(value);
    }

    private static BudgetView budget(double percentUsed, List<Integer> thresholds, String status) {
        return new BudgetView(
                BUDGET_ID, "Food", null, null, money(15000), "INR", "monthly",
                LocalDate.of(2025, 1, 1), null, false, thresholds, true,
                LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), 11, 31,
                money((long) (150 * percentUsed)), BigDecimal.ZERO, money(15000),
                money(15000 - (long) (150 * percentUsed)), percentUsed, money(15000), status);
    }

    private static CardView card(String status, LocalDate dueDate, Long daysUntilDue,
            BigDecimal remainingDue) {
        return new CardView(
                CARD_ID, "HDFC Card", "4321", "INR", false,
                money(200000), money(50000), money(150000), 25.0,
                5, 25, LocalDate.of(2025, 3, 5), dueDate, LocalDate.of(2025, 4, 5), daysUntilDue,
                money(50000), money(2500), LocalDate.of(2025, 3, 5),
                money(10000), BigDecimal.ZERO, remainingDue, money(2500), status);
    }

    private static RecurringView recurring(String state, String status, boolean priceChanged,
            BigDecimal typical, BigDecimal latest, boolean active) {
        return new RecurringView(
                null, "netflix|debit|INR", "Netflix", state, status, "debit",
                null, null, null, null, "INR", "monthly", 30,
                typical, latest, false, priceChanged, 6,
                LocalDate.of(2024, 10, 5), LocalDate.of(2025, 3, 5), LocalDate.of(2025, 4, 5),
                16L, latest, latest.multiply(BigDecimal.valueOf(12)), true, active,
                0.9, List.of(), null);
    }

    private static List<Alert> build(Inputs inputs) {
        return AlertBuilder.build(inputs, TODAY);
    }

    private static Inputs nothing() {
        return new Inputs(List.of(), List.of(), List.of(), 0, null);
    }

    private static Inputs budgets(BudgetView... views) {
        return new Inputs(List.of(views), List.of(), List.of(), 0, null);
    }

    private static Inputs cards(CardView... views) {
        return new Inputs(List.of(), List.of(views), List.of(), 0, null);
    }

    private static Inputs payments(RecurringView... views) {
        return new Inputs(List.of(), List.of(), List.of(views), 0, null);
    }

    @Nested
    @DisplayName("nothing to say")
    class Quiet {

        @Test
        void staysSilentWhenAllIsWell() {
            assertThat(build(nothing())).isEmpty();
        }

        @Test
        void staysSilentAboutABudgetWithRoomLeft() {
            assertThat(build(budgets(budget(45, List.of(80), "on_track")))).isEmpty();
        }

        @Test
        void staysSilentAboutACardWithNothingOwed() {
            assertThat(build(cards(card("clear", LocalDate.of(2025, 3, 25), 5L, BigDecimal.ZERO))))
                    .isEmpty();
        }

        @Test
        void staysSilentAboutAPaidStatement() {
            assertThat(build(cards(card("paid", LocalDate.of(2025, 3, 25), 5L, BigDecimal.ZERO))))
                    .isEmpty();
        }

        @Test
        void staysSilentWhenTheUserAlreadyPaidTheMinimum() {
            // They made a decision about this bill. Reminding someone of a
            // choice they made on purpose is how a list gets ignored.
            assertThat(build(cards(card("minimum_met", LocalDate.of(2025, 3, 25), 5L, money(47500)))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("budget thresholds")
    class Budgets {

        @Test
        void warnsWhenTheUsersOwnThresholdIsCrossed() {
            List<Alert> alerts = build(budgets(budget(85, List.of(80), "warning")));

            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).type()).isEqualTo(AlertType.BUDGET_THRESHOLD);
            assertThat(alerts.get(0).severity()).isEqualTo("warning");
            assertThat(alerts.get(0).title()).isEqualTo("Food is at 85% of its budget");
        }

        @Test
        void raisesOnlyTheHighestThresholdCrossed() {
            // 75% to 105% in one purchase crosses 80, 90 and 100. Three alerts
            // for one event would bury everything else, and the first two are
            // no longer true.
            List<Alert> alerts = build(budgets(budget(105, List.of(80, 90), "over")));

            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).key()).endsWith(":100");
        }

        @Test
        void treatsBeingOverBudgetAsUrgent() {
            List<Alert> alerts = build(budgets(budget(105, List.of(80), "over")));

            assertThat(alerts.get(0).severity()).isEqualTo("urgent");
            assertThat(alerts.get(0).title()).isEqualTo("Food is over budget");
        }

        @Test
        void alwaysTreatsAHundredAsAThreshold() {
            // Someone who set a single reminder at 50% did not mean they stop
            // caring once the money is gone.
            List<Alert> alerts = build(budgets(budget(120, List.of(50), "over")));

            assertThat(alerts.get(0).key()).endsWith(":100");
        }

        @Test
        void respectsAThresholdTheUserSetAboveTheDefault() {
            assertThat(build(budgets(budget(85, List.of(95), "on_track")))).isEmpty();
        }

        @Test
        void keysOnThePeriodSoLastMonthsDismissalDoesNotCarry() {
            Alert alert = build(budgets(budget(85, List.of(80), "warning"))).get(0);

            assertThat(alert.key()).isEqualTo("budget:%s:2025-03-01:80".formatted(BUDGET_ID));
        }

        @Test
        void ignoresBudgetsThatHaveNotStartedOrHaveEnded() {
            assertThat(build(budgets(budget(150, List.of(80), "upcoming")))).isEmpty();
            assertThat(build(budgets(budget(150, List.of(80), "ended")))).isEmpty();
        }

        @Test
        void saysHowMuchIsSpentAndHowLongIsLeft() {
            Alert alert = build(budgets(budget(85, List.of(80), "warning"))).get(0);

            assertThat(alert.body()).contains("12,750").contains("15,000").contains("11 days");
        }

        @Test
        void pointsAtTheBudgetsPage() {
            assertThat(build(budgets(budget(85, List.of(80), "warning"))).get(0).href())
                    .isEqualTo("/budgets");
        }
    }

    @Nested
    @DisplayName("card dues")
    class Cards {

        @Test
        void remindsShortlyBeforeTheDueDate() {
            List<Alert> alerts = build(cards(card("due", LocalDate.of(2025, 3, 25), 5L, money(47500))));

            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).title()).isEqualTo("HDFC Card payment is due in 5 days");
            assertThat(alerts.get(0).severity()).isEqualTo("warning");
        }

        @Test
        void staysQuietWhileTheDueDateIsStillFarOff() {
            assertThat(build(cards(card("due", LocalDate.of(2025, 4, 25), 36L, money(47500)))))
                    .isEmpty();
        }

        @Test
        void saysTodayRatherThanInZeroDays() {
            List<Alert> alerts = build(cards(card("due", LocalDate.of(2025, 3, 20), 0L, money(47500))));

            assertThat(alerts.get(0).title()).isEqualTo("HDFC Card payment is due today");
        }

        @Test
        void treatsAMissedPaymentAsUrgent() {
            List<Alert> alerts = build(cards(card("overdue", LocalDate.of(2025, 3, 15), -5L, money(47500))));

            assertThat(alerts.get(0).severity()).isEqualTo("urgent");
            assertThat(alerts.get(0).title()).isEqualTo("HDFC Card payment is overdue");
            assertThat(alerts.get(0).body()).contains("5 days ago");
        }

        @Test
        void keysOnTheDueDateSoNextMonthStillSpeaksUp() {
            Alert alert = build(cards(card("due", LocalDate.of(2025, 3, 25), 5L, money(47500)))).get(0);

            assertThat(alert.key()).isEqualTo("card:%s:2025-03-25".formatted(CARD_ID));
        }

        @Test
        void saysWhatIsActuallyLeftToPay() {
            Alert alert = build(cards(card("due", LocalDate.of(2025, 3, 25), 5L, money(47500)))).get(0);

            assertThat(alert.body()).contains("47,500");
        }

        @Test
        void usesTheSingularForOneDay() {
            List<Alert> alerts = build(cards(card("due", LocalDate.of(2025, 3, 21), 1L, money(100))));

            assertThat(alerts.get(0).title()).isEqualTo("HDFC Card payment is due in 1 day");
        }
    }

    @Nested
    @DisplayName("price changes")
    class Prices {

        @Test
        void reportsARise() {
            List<Alert> alerts = build(payments(
                    recurring("confirmed", "active", true, money(499), money(649), true)));

            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).title()).isEqualTo("Netflix now costs ₹649");
            assertThat(alerts.get(0).severity()).isEqualTo("warning");
        }

        @Test
        void putsTheRiseInYearlyTerms() {
            Alert alert = build(payments(
                    recurring("confirmed", "active", true, money(499), money(649), true))).get(0);

            assertThat(alert.body()).contains("1,800").contains("more");
        }

        @Test
        void treatsAPriceCutAsMerelyInteresting() {
            List<Alert> alerts = build(payments(
                    recurring("confirmed", "active", true, money(649), money(499), true)));

            assertThat(alerts.get(0).severity()).isEqualTo("info");
            assertThat(alerts.get(0).body()).contains("Down").contains("less");
        }

        @Test
        void keysOnTheNewPriceSoASecondRiseIsHeard() {
            Alert alert = build(payments(
                    recurring("confirmed", "active", true, money(499), money(649), true))).get(0);

            assertThat(alert.key()).isEqualTo("price:netflix|debit|INR:649");
        }

        @Test
        void ignoresSeriesTheUserNeverConfirmed() {
            assertThat(build(payments(
                    recurring("suggested", "active", true, money(499), money(649), true))))
                    .isEmpty();
        }

        @Test
        void staysSilentWhenThePriceHeld() {
            assertThat(build(payments(
                    recurring("confirmed", "active", false, money(499), money(499), true))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("overdue subscriptions")
    class Overdue {

        @Test
        void mentionsASubscriptionThatHasNotCharged() {
            List<Alert> alerts = build(payments(
                    recurring("confirmed", "overdue", false, money(499), money(499), true)));

            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).type()).isEqualTo(AlertType.RECURRING_OVERDUE);
            assertThat(alerts.get(0).title()).isEqualTo("Netflix has not been charged");
        }

        @Test
        void doesNotShoutAboutIt() {
            // Cancelled, or simply not imported yet. Shouting would be wrong
            // most of the time.
            List<Alert> alerts = build(payments(
                    recurring("confirmed", "overdue", false, money(499), money(499), true)));

            assertThat(alerts.get(0).severity()).isEqualTo("info");
            assertThat(alerts.get(0).body()).contains("cancelled").contains("not been imported");
        }

        @Test
        void ignoresAPausedSeries() {
            assertThat(build(payments(
                    recurring("confirmed", "overdue", false, money(499), money(499), false))))
                    .isEmpty();
        }

        @Test
        void ignoresASeriesThatIsSimplyDue() {
            assertThat(build(payments(
                    recurring("confirmed", "due", false, money(499), money(499), true))))
                    .isEmpty();
        }

        @Test
        void keysOnTheExpectedDate() {
            Alert alert = build(payments(
                    recurring("confirmed", "overdue", false, money(499), money(499), true))).get(0);

            assertThat(alert.key()).isEqualTo("overdue:netflix|debit|INR:2025-04-05");
        }
    }

    @Nested
    @DisplayName("pending duplicates")
    class Duplicates {

        private static final UUID NEWEST = UUID.fromString("33333333-3333-3333-3333-333333333333");

        @Test
        void raisesTheQueue() {
            List<Alert> alerts = build(
                    new Inputs(List.of(), List.of(), List.of(), 3, NEWEST));

            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).title()).isEqualTo("3 possible duplicates to review");
            assertThat(alerts.get(0).href()).isEqualTo("/review");
        }

        @Test
        void usesTheSingular() {
            List<Alert> alerts = build(
                    new Inputs(List.of(), List.of(), List.of(), 1, NEWEST));

            assertThat(alerts.get(0).title()).isEqualTo("1 possible duplicate to review");
        }

        @Test
        void keysOnTheNewestCandidateRatherThanTheCount() {
            // A key containing the count would come back every time the number
            // moved, including when the user cleared one.
            Alert alert = build(new Inputs(List.of(), List.of(), List.of(), 3, NEWEST)).get(0);

            assertThat(alert.key()).isEqualTo("duplicates:%s".formatted(NEWEST));
        }

        @Test
        void staysSilentOnAnEmptyQueue() {
            assertThat(build(new Inputs(List.of(), List.of(), List.of(), 0, null))).isEmpty();
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        void putsUrgentFirst() {
            List<Alert> alerts = build(new Inputs(
                    List.of(budget(105, List.of(80), "over")),
                    List.of(card("due", LocalDate.of(2025, 3, 25), 5L, money(47500))),
                    List.of(recurring("confirmed", "overdue", false, money(499), money(499), true)),
                    2, UUID.randomUUID()));

            assertThat(alerts).hasSize(4);
            assertThat(alerts).extracting(Alert::severity)
                    .containsExactly("urgent", "warning", "warning", "info");
        }

        @Test
        void ordersEqualSeveritiesByRecency() {
            List<Alert> alerts = build(new Inputs(
                    List.of(),
                    List.of(card("overdue", LocalDate.of(2025, 3, 15), -5L, money(47500))),
                    List.of(),
                    0, null));

            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).occurredOn()).isEqualTo(LocalDate.of(2025, 3, 15));
        }

        @Test
        void producesOneAlertPerSituationNotPerSubject() {
            // A single subscription can be both overdue and freshly repriced;
            // they are different facts and both are worth saying.
            List<Alert> alerts = build(payments(
                    recurring("confirmed", "overdue", true, money(499), money(649), true)));

            assertThat(alerts).hasSize(2);
            assertThat(alerts).extracting(Alert::type)
                    .containsExactlyInAnyOrder(AlertType.PRICE_CHANGED, AlertType.RECURRING_OVERDUE);
        }
    }
}
