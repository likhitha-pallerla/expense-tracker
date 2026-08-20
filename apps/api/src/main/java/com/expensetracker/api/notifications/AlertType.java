package com.expensetracker.api.notifications;

/**
 * The kinds of thing worth interrupting someone for.
 *
 * <p>Deliberately short. A notification centre earns its place by telling the
 * user something they would not otherwise have looked for; one that repeats
 * what is already on the page they just left is trained away within a week.
 * Every type here is either time-sensitive or invisible from the dashboard.
 *
 * <p>{@code href} is where the alert can actually be acted on. An alert with
 * nowhere to go is a complaint.
 */
public enum AlertType {

    /** A budget crossed one of the thresholds the user set on it. */
    BUDGET_THRESHOLD("budget_threshold", "/budgets"),

    /** A card statement is due, or already late. */
    CARD_DUE("card_due", "/cards"),

    /** Possible duplicates are waiting to be judged. */
    DUPLICATES_PENDING("duplicates_pending", "/review"),

    /** A confirmed subscription changed price. */
    PRICE_CHANGED("price_changed", "/recurring"),

    /** A confirmed subscription is late — cancelled, or not yet imported. */
    RECURRING_OVERDUE("recurring_overdue", "/recurring");

    private final String key;
    private final String href;

    AlertType(String key, String href) {
        this.key = key;
        this.href = href;
    }

    public String key() {
        return key;
    }

    public String href() {
        return href;
    }
}
