package com.expensetracker.api.entry;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What one typed sentence turned into.
 *
 * <p>Deliberately a <em>draft</em>. Nothing here has been written down: the
 * user is shown what was understood and confirms it. Free-text entry is the one
 * place in this system where the input is genuinely ambiguous — "500 mom" could
 * be a gift, a repayment or a transfer — and a parser that acted on its own
 * reading would file those silently and wrongly. Confirmation costs one tap and
 * removes the entire class of problem.
 *
 * <p>{@code understood} is not a probability and does not come from a model. It
 * is a plain count of how many fields were recognised, used only to decide how
 * much of the confirmation form to pre-fill. Calling it confidence would invite
 * someone to threshold on it later as though it meant something.
 *
 * @param merchant      who was paid, when the sentence named them
 * @param description   what it was for
 * @param accountHint   the words that looked like an account, unresolved
 * @param categoryHint  the words that looked like a category, unresolved
 * @param dateExplicit  false when the date fell back to today
 * @param source        which reader produced this: the rules, or the model
 */
public record EntryDraft(
        BigDecimal amount,
        String direction,
        String merchant,
        String description,
        String accountHint,
        String categoryHint,
        LocalDate occurredOn,
        boolean dateExplicit,
        String source,
        String problem) {

    public static final String SOURCE_RULES = "rules";
    public static final String SOURCE_AI = "ai";

    /**
     * Nothing usable came out.
     *
     * <p>The message names the one thing that is always required, because "could
     * not understand" tells somebody nothing about what to type instead.
     */
    public static EntryDraft unreadable(String problem) {
        return new EntryDraft(null, null, null, null, null, null, null, false, SOURCE_RULES,
                problem == null ? "Add an amount, like \"spent 250 on lunch\"." : problem);
    }

    public boolean isSuccess() {
        return problem == null && amount != null;
    }

    public boolean isCredit() {
        return "credit".equals(direction);
    }

    /** How much of the sentence was recognised, for pre-filling the form. */
    public int understood() {
        int fields = 0;
        if (amount != null) {
            fields++;
        }
        if (merchant != null) {
            fields++;
        }
        if (categoryHint != null) {
            fields++;
        }
        if (accountHint != null) {
            fields++;
        }
        if (dateExplicit) {
            fields++;
        }
        return fields;
    }

    EntryDraft withSource(String newSource) {
        return new EntryDraft(amount, direction, merchant, description, accountHint,
                categoryHint, occurredOn, dateExplicit, newSource, problem);
    }
}
