package com.expensetracker.api.entry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the app should put on the confirmation form.
 *
 * <p>The hints are returned alongside the resolved ids on purpose. When "hdfc
 * card" matched an account, the id is enough; when it matched nothing — or
 * matched two things equally — the words the user typed are the only way the
 * screen can say <em>why</em> the account box is empty. Dropping the hint would
 * leave the form silently blank and the user retyping something they had
 * already said.
 *
 * @param accountId    resolved account, or null when nothing matched clearly
 * @param categoryId   resolved category, or null
 * @param accountHint  the words that looked like an account, always returned
 * @param understood   how many fields were recognised; not a probability
 * @param source       "rules" or "ai", so the screen can be honest about it
 */
public record EntrySuggestion(
        BigDecimal amount,
        String direction,
        String merchant,
        String description,
        LocalDate occurredOn,
        boolean dateExplicit,
        UUID accountId,
        String accountName,
        UUID categoryId,
        String categoryName,
        String accountHint,
        String categoryHint,
        int understood,
        String source,
        String problem) {

    public static EntrySuggestion unreadable(String problem, String source) {
        return new EntrySuggestion(null, null, null, null, null, false,
                null, null, null, null, null, null, 0,
                source == null ? EntryDraft.SOURCE_RULES : source,
                problem == null ? "Add an amount, like \"spent 250 on lunch\"." : problem);
    }

    public boolean isSuccess() {
        return problem == null && amount != null;
    }
}
