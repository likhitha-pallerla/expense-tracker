package com.expensetracker.api.goals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A goal as the interface needs it: the settings, the arithmetic, and enough
 * words that the caller never has to invent the message itself.
 *
 * @param progress       everything derived; see {@link GoalProgress}
 * @param contributions  most recent first, and only populated when a single
 *                       goal is fetched — a list of twenty goals does not need
 *                       every deposit ever made
 */
public record GoalView(
        UUID id,
        String name,
        BigDecimal targetAmount,
        String currency,
        LocalDate targetDate,
        BigDecimal monthlyTarget,
        UUID accountId,
        String accountName,
        String notes,
        String status,
        OffsetDateTime achievedAt,
        OffsetDateTime createdAt,
        GoalProgress progress,
        List<GoalContribution> contributions,
        String headline) {

    /** Whether it is still being pursued, for filtering and sort order. */
    @JsonProperty("isActive")
    public boolean isActive() {
        return GoalStatus.ACTIVE.dbValue().equals(status);
    }

    @JsonProperty("isCancelled")
    public boolean isCancelled() {
        return GoalStatus.CANCELLED.dbValue().equals(status);
    }

    /**
     * One sentence describing where the goal stands.
     *
     * <p>Written here rather than in the web app because the mobile app will
     * need exactly the same sentence, and two implementations of this would
     * drift into telling the user two different things about one goal.
     *
     * <p>The order of these branches is the point. "Done" outranks everything;
     * a cancelled goal is not chased about pace; a missed deadline is reported
     * before anything about what to save monthly, because there is no longer a
     * monthly amount that arrives "in time"; not having started reads
     * differently from being behind; and no deadline means no verdict.
     */
    static String headlineFor(GoalProgress p, GoalStatus status, LocalDate targetDate) {
        if (p.achieved()) return "Reached. Nice.";
        if (status == GoalStatus.CANCELLED) return "Cancelled.";
        if (status == GoalStatus.PAUSED) return "Paused — nothing expected for now.";

        if (p.overdue()) {
            return "The date has passed and " + moneyPhrase(p.remaining()) + " is still to go.";
        }

        if (p.notStarted()) {
            return targetDate == null
                    ? "Nothing put aside yet."
                    : "Nothing put aside yet. " + moneyPhrase(p.requiredPerMonth())
                            + " a month gets you there in time.";
        }

        if (targetDate == null) {
            return p.percent() + "% of the way there.";
        }

        if (Boolean.TRUE.equals(p.onTrack())) {
            return "On track at your current pace.";
        }
        if (Boolean.FALSE.equals(p.onTrack())) {
            return p.actualPerMonth() == null || p.actualPerMonth().signum() <= 0
                    ? "Nothing has gone in lately. " + moneyPhrase(p.requiredPerMonth())
                            + " a month is what the date needs."
                    : "Behind: you are putting in " + moneyPhrase(p.actualPerMonth())
                            + " a month and the date needs " + moneyPhrase(p.requiredPerMonth()) + ".";
        }

        return "Too early to judge the pace. " + moneyPhrase(p.requiredPerMonth())
                + " a month is what the date needs.";
    }

    /** Plain digits with separators; the currency symbol is the caller's job. */
    private static String moneyPhrase(BigDecimal amount) {
        if (amount == null) return "Some more";
        return String.format("%,.0f", amount);
    }
}
