package com.expensetracker.api.parsing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a rule made of one message.
 *
 * <p>A failed parse is as important as a successful one. "We could not read
 * this" with a reason is something the user can act on; silence is not.
 *
 * @param ruleId     which rule produced this, so a bad rule can be found
 * @param ruleName   the same, in words, for the interface
 * @param dateExact  false when the date fell back to when the mail arrived
 * @param problem    why nothing usable came out, or null on success
 */
public record ParsedAlert(
        UUID ruleId,
        String ruleName,
        BigDecimal amount,
        String direction,
        String merchant,
        String last4,
        Instant occurredAt,
        String reference,
        boolean dateExact,
        String problem) {

    /** Nothing here looked like a payment at all. */
    static ParsedAlert noRule() {
        return new ParsedAlert(null, null, null, null, null, null, null, null, false,
                "No rule recognised this message. It may not be a payment alert.");
    }

    /**
     * A rule matched but the message did not carry enough to record a payment.
     *
     * <p>The missing fields are named rather than summarised: "no amount" tells
     * the user something, "could not parse" does not.
     */
    static ParsedAlert incomplete(ParserRule rule, List<String> missing) {
        return new ParsedAlert(rule.id(), rule.name(), null, null, null, null, null, null, false,
                "Matched \"" + rule.name() + "\" but could not find the "
                        + String.join(" or the ", missing) + ".");
    }

    public boolean isSuccess() {
        return problem == null;
    }

    public boolean isCredit() {
        return "credit".equals(direction);
    }
}
