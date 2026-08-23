package com.expensetracker.api.goals;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the user has decided about a goal, as opposed to what the numbers say.
 *
 * <p>Deliberately kept apart from {@link GoalProgress#achieved()}. Reaching the
 * target is arithmetic; pausing or abandoning a goal is a decision. Collapsing
 * the two would mean a withdrawal could silently un-achieve a goal, or that
 * pausing one would be read as giving up on it.
 *
 * <p>Mirrors the {@code goal_status} enum in the database.
 */
public enum GoalStatus {

    ACTIVE("active"),
    ACHIEVED("achieved"),
    PAUSED("paused"),
    CANCELLED("cancelled");

    private final String dbValue;

    GoalStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** Paused and cancelled goals are still counted, just not chased. */
    public boolean isOpen() {
        return this == ACTIVE || this == ACHIEVED;
    }

    /**
     * @param value null or blank defaults to active, since creating a goal you
     *              are not pursuing is not a thing anyone does
     */
    public static GoalStatus from(String value) {
        if (value == null || value.isBlank()) return ACTIVE;
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        for (GoalStatus status : values()) {
            if (status.dbValue.equals(normalised)) return status;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Unknown status '" + value + "'. Use active, achieved, paused or cancelled.");
    }
}
