package com.expensetracker.api.notifications;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.expensetracker.api.budgets.BudgetView;
import com.expensetracker.api.cards.CardView;
import com.expensetracker.api.recurring.Cadence;
import com.expensetracker.api.recurring.RecurringView;
import com.expensetracker.api.web.Money;

/**
 * Turns live state into alerts.
 *
 * <p>Pure: no database, no clock, no configuration. It is handed the same views
 * the budgets, cards and recurring pages render, so an alert can never claim
 * something those pages contradict.
 *
 * <h2>Keys are the whole design</h2>
 *
 * <p>An alert is recomputed from scratch on every read, so the only way to
 * remember that the user dismissed one is to name it. A key has to be stable
 * enough that the same situation produces the same name every time, and
 * specific enough that silencing one alert cannot silence a different one.
 *
 * <p>That means a key carries the situation, not just the subject. Dismissing
 * March's 80% budget warning must not hide April's, and must not hide the
 * breach of 100% that follows it a week later — so the period and the threshold
 * are both in the key. Dismissing this month's card reminder must not hide next
 * month's, so the due date is in the key. Waving away a price rise must not hide
 * the next one, so the new price is in the key.
 *
 * <p>The pending-duplicates alert uses the newest candidate's id for the same
 * reason: dismissing it should quieten the queue as it stands, and speak up
 * again the moment something genuinely new lands in it.
 */
final class AlertBuilder {

    /** Far enough ahead to act on, close enough to still be true. */
    private static final int CARD_DUE_WINDOW_DAYS = 7;

    private AlertBuilder() {
    }

    static List<Alert> build(Inputs inputs, LocalDate today) {
        List<Alert> alerts = new ArrayList<>();

        for (BudgetView budget : inputs.budgets()) {
            budgetAlert(budget).ifPresent(alerts::add);
        }
        for (CardView card : inputs.cards()) {
            cardAlert(card, today).ifPresent(alerts::add);
        }
        for (RecurringView payment : inputs.recurring()) {
            priceAlert(payment).ifPresent(alerts::add);
            overdueAlert(payment).ifPresent(alerts::add);
        }
        duplicatesAlert(inputs, today).ifPresent(alerts::add);

        alerts.sort(Comparator.comparingInt(Alert::rank)
                .thenComparing(Alert::occurredOn, Comparator.reverseOrder())
                .thenComparing(Alert::title));
        return List.copyOf(alerts);
    }

    /**
     * Only the highest threshold crossed.
     *
     * <p>A budget that runs from 75% to 105% in one purchase has crossed 80, 90
     * and 100. Raising three alerts for one event would bury everything else in
     * the list, and the first two are no longer true — the budget is not at 80%,
     * it is over.
     *
     * <p>100 is always treated as a threshold whether or not the user set it.
     * Someone who configured a single reminder at 50% did not mean they stop
     * caring once the money is gone.
     */
    private static Optional<Alert> budgetAlert(BudgetView budget) {
        if (!budget.isActive() || !isLive(budget.status())) {
            return Optional.empty();
        }

        int crossed = -1;
        for (int threshold : withHundred(budget.alertThresholds())) {
            if (budget.percentUsed() >= threshold && threshold > crossed) {
                crossed = threshold;
            }
        }
        if (crossed < 0) {
            return Optional.empty();
        }

        boolean over = budget.percentUsed() >= 100;
        String name = budget.name();
        String spent = Money.format(budget.spent(), budget.currency());
        String limit = Money.format(budget.limit(), budget.currency());

        String title = over
                ? "%s is over budget".formatted(name)
                : "%s is at %.0f%% of its budget".formatted(name, budget.percentUsed());

        String body = over
                ? "%s spent against %s, with %d %s of the period left.".formatted(
                        spent, limit, budget.daysRemaining(),
                        budget.daysRemaining() == 1 ? "day" : "days")
                : "%s of %s spent, %d %s left.".formatted(
                        spent, limit, budget.daysRemaining(),
                        budget.daysRemaining() == 1 ? "day" : "days");

        return Optional.of(new Alert(
                "budget:%s:%s:%d".formatted(budget.id(), budget.periodStart(), crossed),
                AlertType.BUDGET_THRESHOLD,
                over ? Alert.URGENT : Alert.WARNING,
                title, body, AlertType.BUDGET_THRESHOLD.href(), budget.periodStart()));
    }

    /**
     * Only cards with something actually left to pay.
     *
     * <p>{@code minimum_met} is deliberately not alerted on. The user has
     * already made a decision about that bill, and reminding them of a choice
     * they made on purpose is how a notification list gets ignored.
     */
    private static Optional<Alert> cardAlert(CardView card, LocalDate today) {
        boolean overdue = "overdue".equals(card.status());
        boolean due = "due".equals(card.status());
        if (!overdue && !due) {
            return Optional.empty();
        }
        if (card.dueDate() == null || card.remainingDue() == null
                || card.remainingDue().signum() <= 0) {
            return Optional.empty();
        }
        if (due && (card.daysUntilDue() == null || card.daysUntilDue() > CARD_DUE_WINDOW_DAYS)) {
            return Optional.empty();
        }

        String amount = Money.format(card.remainingDue(), card.currency());
        long days = overdue
                ? ChronoUnit.DAYS.between(card.dueDate(), today)
                : card.daysUntilDue();

        String title = overdue
                ? "%s payment is overdue".formatted(card.name())
                : days == 0
                        ? "%s payment is due today".formatted(card.name())
                        : "%s payment is due in %d %s".formatted(
                                card.name(), days, days == 1 ? "day" : "days");

        String body = overdue
                ? "%s was due %d %s ago.".formatted(amount, days, days == 1 ? "day" : "days")
                : "%s outstanding on the statement.".formatted(amount);

        return Optional.of(new Alert(
                "card:%s:%s".formatted(card.accountId(), card.dueDate()),
                AlertType.CARD_DUE,
                overdue ? Alert.URGENT : Alert.WARNING,
                title, body, AlertType.CARD_DUE.href(), card.dueDate()));
    }

    /**
     * A price change the user has not been told about.
     *
     * <p>The new amount is in the key, so a second rise raises a second alert
     * rather than being swallowed by the first dismissal.
     */
    private static Optional<Alert> priceAlert(RecurringView payment) {
        if (!"confirmed".equals(payment.state()) || !payment.priceChanged()
                || payment.latestAmount() == null || payment.typicalAmount() == null) {
            return Optional.empty();
        }

        BigDecimal was = payment.typicalAmount();
        BigDecimal now = payment.latestAmount();
        boolean rose = now.compareTo(was) > 0;

        String title = "%s now costs %s".formatted(
                payment.name(), Money.format(now, payment.currency()));
        String body = "%s from %s. That is %s a year %s.".formatted(
                rose ? "Up" : "Down",
                Money.format(was, payment.currency()),
                Money.format(now.subtract(was).abs()
                        .multiply(BigDecimal.valueOf(chargesPerYear(payment))),
                        payment.currency()),
                rose ? "more" : "less");

        return Optional.of(new Alert(
                "price:%s:%s".formatted(payment.matchKey(), now.stripTrailingZeros().toPlainString()),
                AlertType.PRICE_CHANGED,
                rose ? Alert.WARNING : Alert.INFO,
                title, body, AlertType.PRICE_CHANGED.href(),
                payment.lastCharge()));
    }

    /**
     * A subscription that should have been charged by now.
     *
     * <p>Kept at {@code info}, because the two explanations are a cancellation
     * the user already knows about and an import that has not run yet. Shouting
     * about either would be wrong most of the time.
     */
    private static Optional<Alert> overdueAlert(RecurringView payment) {
        if (!"confirmed".equals(payment.state()) || !payment.isActive()
                || !"overdue".equals(payment.status()) || payment.nextExpected() == null) {
            return Optional.empty();
        }

        String title = "%s has not been charged".formatted(payment.name());
        String body = "Expected around %s. Either it was cancelled, or the charge has not been imported yet."
                .formatted(payment.nextExpected());

        return Optional.of(new Alert(
                "overdue:%s:%s".formatted(payment.matchKey(), payment.nextExpected()),
                AlertType.RECURRING_OVERDUE, Alert.INFO,
                title, body, AlertType.RECURRING_OVERDUE.href(), payment.nextExpected()));
    }

    /**
     * The review queue, keyed on its newest member.
     *
     * <p>A key of just "duplicates" would be silenced forever by one dismissal.
     * A key containing the count would come back every time the number moved,
     * including when the user cleared one. Naming the newest candidate means
     * dismissing settles the queue as it stands and speaks up again only when
     * something new arrives.
     */
    private static Optional<Alert> duplicatesAlert(Inputs inputs, LocalDate today) {
        if (inputs.pendingDuplicates() <= 0 || inputs.newestDuplicate() == null) {
            return Optional.empty();
        }

        long count = inputs.pendingDuplicates();
        return Optional.of(new Alert(
                "duplicates:%s".formatted(inputs.newestDuplicate()),
                AlertType.DUPLICATES_PENDING, Alert.WARNING,
                count == 1
                        ? "1 possible duplicate to review"
                        : "%d possible duplicates to review".formatted(count),
                "Confirm which are the same payment so your totals are not counted twice.",
                AlertType.DUPLICATES_PENDING.href(), today));
    }

    // ---- helpers -----------------------------------------------------------

    private static boolean isLive(String status) {
        return switch (status) {
            case "on_track", "warning", "over" -> true;
            default -> false;
        };
    }

    private static List<Integer> withHundred(List<Integer> thresholds) {
        List<Integer> all = new ArrayList<>(thresholds);
        if (!all.contains(100)) {
            all.add(100);
        }
        return all;
    }

    /**
     * Enumerated from the cadence rather than derived from its length in days,
     * which would bill a monthly plan 12.2 times a year and overstate the
     * difference a price change makes. Read from {@link Cadence} rather than
     * copied, so the two can never disagree.
     */
    private static int chargesPerYear(RecurringView payment) {
        return Cadence.from(payment.cadence()).map(Cadence::chargesPerYear).orElse(12);
    }

    /** Everything the alerts are derived from, gathered before any judgement. */
    record Inputs(
            List<BudgetView> budgets,
            List<CardView> cards,
            List<RecurringView> recurring,
            long pendingDuplicates,
            UUID newestDuplicate) {
    }
}
