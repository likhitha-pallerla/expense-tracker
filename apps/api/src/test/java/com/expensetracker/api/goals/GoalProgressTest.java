package com.expensetracker.api.goals;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GoalProgressTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static GoalProgress progress(
            String target, String saved, LocalDate targetDate,
            LocalDate firstContribution, String monthlyTarget) {
        return GoalProgress.of(
                money(target), money(saved), targetDate, firstContribution,
                monthlyTarget == null ? null : money(monthlyTarget), TODAY);
    }

    @Nested
    @DisplayName("the basics")
    class Basics {

        @Test
        void reportsWhatIsLeft() {
            GoalProgress p = progress("100000", "25000", null, null, null);

            assertThat(p.remaining()).isEqualByComparingTo("75000");
            assertThat(p.percent()).isEqualByComparingTo("25.00");
            assertThat(p.achieved()).isFalse();
        }

        @Test
        void treatsAnExactHitAsAchieved() {
            GoalProgress p = progress("100000", "100000", null, null, null);

            assertThat(p.achieved()).isTrue();
            assertThat(p.remaining()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("saving past the target is a met goal, not 143% of one")
        void capsPercentAtOneHundred() {
            GoalProgress p = progress("70000", "100000", null, null, null);

            assertThat(p.percent()).isEqualByComparingTo("100.00");
            assertThat(p.remaining()).isEqualByComparingTo("0");
            assertThat(p.achieved()).isTrue();
        }

        @Test
        @DisplayName("a withdrawal below zero does not produce negative progress")
        void floorsPercentAtZero() {
            GoalProgress p = progress("50000", "-2000", null, null, null);

            assertThat(p.percent()).isEqualByComparingTo("0.00");
            assertThat(p.remaining()).isEqualByComparingTo("52000");
        }

        @Test
        void survivesAZeroTargetWithoutDividingByIt() {
            GoalProgress p = GoalProgress.of(
                    BigDecimal.ZERO, money("500"), null, null, null, TODAY);

            assertThat(p.percent()).isEqualByComparingTo("0.00");
            assertThat(p.achieved()).isFalse();
        }

        @Test
        void treatsNullsAsZero() {
            GoalProgress p = GoalProgress.of(null, null, null, null, null, TODAY);

            assertThat(p.saved()).isEqualByComparingTo("0");
            assertThat(p.notStarted()).isTrue();
        }
    }

    @Nested
    @DisplayName("without a target date")
    class NoDeadline {

        @Test
        @DisplayName("there is nothing to be behind on, so no verdict is given")
        void withholdsEveryDeadlineJudgement() {
            GoalProgress p = progress("100000", "10000", null, TODAY.minusMonths(6), null);

            assertThat(p.daysLeft()).isNull();
            assertThat(p.monthsLeft()).isNull();
            assertThat(p.requiredPerMonth()).isNull();
            assertThat(p.onTrack()).isNull();
            assertThat(p.overdue()).isFalse();
        }

        @Test
        @DisplayName("but a pace and a finish date are still worth knowing")
        void stillProjectsFromPace() {
            GoalProgress p = progress("120000", "60000", null, TODAY.minusMonths(6), null);

            assertThat(p.actualPerMonth()).isEqualByComparingTo("10034.13");
            assertThat(p.projectedDate()).isAfter(TODAY);
        }

        @Test
        void neverClaimsAPlanFallsShort() {
            GoalProgress p = progress("100000", "10000", null, TODAY.minusMonths(6), "500");

            assertThat(p.planShortfall()).isNull();
            assertThat(p.planFallsShort()).isFalse();
        }
    }

    @Nested
    @DisplayName("what the date requires")
    class Required {

        @Test
        void spreadsTheRemainderOverTheMonthsLeft() {
            GoalProgress p = progress(
                    "120000", "0", TODAY.plusDays(365), null, null);

            assertThat(p.monthsLeft()).isEqualByComparingTo("11.9920");
            assertThat(p.requiredPerMonth()).isEqualByComparingTo("10006.64");
        }

        @Test
        @DisplayName("a date reached today asks for the remainder now, not per month")
        void doesNotDivideByZeroMonths() {
            GoalProgress p = progress("50000", "20000", TODAY, null, null);

            assertThat(p.daysLeft()).isZero();
            assertThat(p.requiredPerMonth()).isEqualByComparingTo("30000");
        }

        @Test
        @DisplayName("a date in the past asks for the remainder now, never a negative")
        void doesNotDivideByNegativeMonths() {
            GoalProgress p = progress("50000", "20000", TODAY.minusDays(40), null, null);

            assertThat(p.daysLeft()).isEqualTo(-40);
            assertThat(p.overdue()).isTrue();
            assertThat(p.requiredPerMonth()).isEqualByComparingTo("30000");
        }

        @Test
        @DisplayName("an achieved goal stops asking for anything")
        void goesQuietOnceReached() {
            GoalProgress p = progress("50000", "50000", TODAY.plusDays(90), TODAY.minusDays(90), null);

            assertThat(p.requiredPerMonth()).isNull();
            assertThat(p.overdue()).isFalse();
            assertThat(p.onTrack()).isTrue();
            assertThat(p.projectedDate()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("a missed date on an achieved goal is not overdue")
        void doesNotNagAboutAMetGoalThatWasLate() {
            GoalProgress p = progress("50000", "51000", TODAY.minusDays(10), TODAY.minusDays(90), null);

            assertThat(p.overdue()).isFalse();
            assertThat(p.achieved()).isTrue();
        }
    }

    @Nested
    @DisplayName("pace")
    class Pace {

        @Test
        @DisplayName("is measured from the first deposit, not from when the goal was created")
        void measuresFromWhenSavingStarted() {
            GoalProgress p = progress("120000", "30000", null, TODAY.minusDays(91), null);

            // 30,000 over 91 days, not over however long the goal has existed.
            assertThat(p.actualPerMonth()).isEqualByComparingTo("10034.13");
        }

        @Test
        @DisplayName("a goal only days old has no pace worth reporting")
        void withholdsAVerdictWhenTooNew() {
            GoalProgress p = progress(
                    "120000", "5000", TODAY.plusDays(300), TODAY.minusDays(3), null);

            assertThat(p.actualPerMonth()).isNull();
            assertThat(p.projectedDate()).isNull();
            assertThat(p.onTrack()).isNull();
        }

        @Test
        void startsJudgingOnceThereIsEnoughHistory() {
            GoalProgress p = progress(
                    "120000", "5000", TODAY.plusDays(300),
                    TODAY.minusDays(GoalProgress.MIN_DAYS_OF_HISTORY), null);

            assertThat(p.actualPerMonth()).isNotNull();
            assertThat(p.onTrack()).isNotNull();
        }

        @Test
        @DisplayName("nothing saved means no pace, even after months")
        void reportsNoPaceWhenNothingHasGoneIn() {
            GoalProgress p = progress("120000", "0", TODAY.plusDays(300), null, null);

            assertThat(p.actualPerMonth()).isNull();
            assertThat(p.projectedDate()).isNull();
            assertThat(p.notStarted()).isTrue();
        }

        @Test
        @DisplayName("a net-negative balance projects to never, not to a date in the past")
        void refusesToProjectFromNegativePace() {
            GoalProgress p = progress("120000", "-5000", null, TODAY.minusMonths(4), null);

            assertThat(p.actualPerMonth()).isNull();
            assertThat(p.projectedDate()).isNull();
        }
    }

    @Nested
    @DisplayName("on track")
    class OnTrack {

        @Test
        void saysYesWhenThePaceArrivesInTime() {
            GoalProgress p = progress(
                    "120000", "60000", TODAY.plusDays(365), TODAY.minusDays(180), null);

            assertThat(p.onTrack()).isTrue();
            assertThat(p.projectedDate()).isBeforeOrEqualTo(TODAY.plusDays(365));
        }

        @Test
        void saysNoWhenThePaceArrivesLate() {
            GoalProgress p = progress(
                    "120000", "10000", TODAY.plusDays(90), TODAY.minusDays(180), null);

            assertThat(p.onTrack()).isFalse();
            assertThat(p.projectedDate()).isAfter(TODAY.plusDays(90));
        }

        @Test
        @DisplayName("says nothing when there is no history to extrapolate from")
        void staysSilentWithoutHistory() {
            GoalProgress p = progress("120000", "0", TODAY.plusDays(90), null, null);

            assertThat(p.onTrack()).isNull();
        }

        @Test
        @DisplayName("arriving exactly on the target date counts as on track")
        void treatsTheDeadlineItselfAsOnTime() {
            // Half saved in 183 days, 183 days left: exactly on pace. This is
            // the case that breaks if the projection is computed from the
            // rounded per-month figure instead of from the raw history.
            GoalProgress p = GoalProgress.of(
                    money("12000"), money("6000"), TODAY.plusDays(183),
                    TODAY.minusDays(183), null, TODAY);

            assertThat(p.projectedDate()).isEqualTo(TODAY.plusDays(183));
            assertThat(p.onTrack()).isTrue();
        }
    }

    @Nested
    @DisplayName("a stated monthly plan")
    class Plan {

        @Test
        void flagsAPlanThatCannotHitTheDate() {
            GoalProgress p = progress("120000", "0", TODAY.plusDays(365), null, "5000");

            assertThat(p.requiredPerMonth()).isEqualByComparingTo("10006.64");
            assertThat(p.planShortfall()).isEqualByComparingTo("5006.64");
            assertThat(p.planFallsShort()).isTrue();
        }

        @Test
        void staysQuietWhenThePlanIsEnough() {
            GoalProgress p = progress("120000", "0", TODAY.plusDays(365), null, "15000");

            assertThat(p.planShortfall()).isEqualByComparingTo("0");
            assertThat(p.planFallsShort()).isFalse();
        }

        @Test
        @DisplayName("is not judged against an achieved goal")
        void ignoresThePlanOnceReached() {
            GoalProgress p = progress("120000", "120000", TODAY.plusDays(365), null, "10");

            assertThat(p.planShortfall()).isNull();
            assertThat(p.planFallsShort()).isFalse();
        }
    }
}
