package com.expensetracker.api.goals;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one sentence a user actually reads.
 *
 * <p>Worth testing on its own because the branch order is load-bearing: every
 * one of these states is true at the same time as several others, and picking
 * the wrong one produces a sentence that is individually accurate and
 * completely misleading.
 */
class GoalViewTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);

    private static String headline(
            String target, String saved, LocalDate targetDate,
            LocalDate firstContribution, GoalStatus status) {

        GoalProgress p = GoalProgress.of(
                new BigDecimal(target), new BigDecimal(saved), targetDate,
                firstContribution, null, TODAY);
        return GoalView.headlineFor(p, status, targetDate);
    }

    @Test
    @DisplayName("a missed deadline is never described as still reachable")
    void putsOverdueAheadOfNotStarted() {
        String line = headline("50000", "0", TODAY.minusDays(40), null, GoalStatus.ACTIVE);

        assertThat(line).contains("date has passed");
        assertThat(line).doesNotContain("in time");
    }

    @Test
    @DisplayName("reaching the target outranks every other state")
    void congratulatesEvenWhenLate() {
        String line = headline("50000", "50000", TODAY.minusDays(40),
                TODAY.minusDays(200), GoalStatus.ACTIVE);

        assertThat(line).isEqualTo("Reached. Nice.");
    }

    @Test
    void doesNotChaseACancelledGoal() {
        String line = headline("50000", "1000", TODAY.minusDays(40),
                TODAY.minusDays(200), GoalStatus.CANCELLED);

        assertThat(line).isEqualTo("Cancelled.");
    }

    @Test
    void doesNotChaseAPausedGoal() {
        String line = headline("50000", "1000", TODAY.plusDays(40),
                TODAY.minusDays(200), GoalStatus.PAUSED);

        assertThat(line).contains("Paused");
    }

    @Test
    @DisplayName("with no deadline it reports progress and passes no judgement")
    void staysNeutralWithoutADate() {
        String line = headline("50000", "10000", null, TODAY.minusDays(200), GoalStatus.ACTIVE);

        assertThat(line).contains("20.00%");
        assertThat(line).doesNotContain("Behind");
        assertThat(line).doesNotContain("track");
    }

    @Test
    void saysWhenThePaceWillArriveInTime() {
        String line = headline("50000", "25000", TODAY.plusDays(365),
                TODAY.minusDays(180), GoalStatus.ACTIVE);

        assertThat(line).isEqualTo("On track at your current pace.");
    }

    @Test
    @DisplayName("being behind names both figures, so the gap is checkable")
    void quantifiesBeingBehind() {
        String line = headline("500000", "10000", TODAY.plusDays(90),
                TODAY.minusDays(180), GoalStatus.ACTIVE);

        assertThat(line).startsWith("Behind:");
        assertThat(line).contains("1,691");
        assertThat(line).contains("165,712");
    }

    @Test
    @DisplayName("a brand-new goal is told what it needs, not that it is failing")
    void withholdsTheVerdictWhileTooNew() {
        String line = headline("120000", "5000", TODAY.plusDays(300),
                TODAY.minusDays(3), GoalStatus.ACTIVE);

        assertThat(line).startsWith("Too early to judge");
        assertThat(line).doesNotContain("Behind");
    }
}
