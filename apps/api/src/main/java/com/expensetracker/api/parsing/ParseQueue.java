package com.expensetracker.api.parsing;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * How much mail is in each state.
 *
 * @param pending waiting to be read
 * @param failed  read and not understood; these are listed for the user
 * @param parsed  already turned into transactions
 */
public record ParseQueue(int pending, int failed, int parsed) {

    @JsonProperty("hasWork")
    public boolean hasWork() {
        return pending > 0;
    }
}
