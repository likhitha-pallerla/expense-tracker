package com.expensetracker.api.notifications;

import java.time.LocalDate;

/**
 * One thing worth telling the user, derived from live state.
 *
 * <p>Nothing here is stored. The alert exists for exactly as long as the
 * situation that produced it: pay the card and it goes, delete the transaction
 * that blew the budget and it goes. A stored alert would outlive its cause and
 * keep insisting on a problem the user had already solved.
 *
 * @param key        stable identity across recomputations, and the only thing
 *                   about an alert that is ever written down
 * @param severity   {@code info}, {@code warning} or {@code urgent}
 * @param occurredOn what the alert is about, for ordering — a due date, a
 *                   billing period, the day a price changed
 */
public record Alert(
        String key,
        AlertType type,
        String severity,
        String title,
        String body,
        String href,
        LocalDate occurredOn) {

    static final String INFO = "info";
    static final String WARNING = "warning";
    static final String URGENT = "urgent";

    /** Urgent first, then the most recent — the order someone would triage in. */
    int rank() {
        return switch (severity) {
            case URGENT -> 0;
            case WARNING -> 1;
            default -> 2;
        };
    }
}
