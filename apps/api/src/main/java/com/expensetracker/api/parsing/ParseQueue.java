package com.expensetracker.api.parsing;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How much mail is in each state.
 *
 * @param pending     waiting to be read
 * @param failed      read and not understood; these are listed for the user
 * @param parsed      already turned into transactions
 * @param quarantined held back because the sender was not recognised; only the
 *                    user can release these, so they are surfaced separately
 *                    from {@code failed}, which is our fault rather than theirs
 */
public record ParseQueue(int pending, int failed, int parsed, int quarantined) {

    @JsonProperty("hasWork")
    public boolean hasWork() {
        return pending > 0;
    }

    /** Whether something is waiting on a decision only the user can make. */
    @JsonProperty("needsAttention")
    public boolean needsAttention() {
        return quarantined > 0;
    }
}
