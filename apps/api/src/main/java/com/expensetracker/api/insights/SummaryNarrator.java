package com.expensetracker.api.insights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.expensetracker.api.ai.AiClient;
import com.expensetracker.api.web.Money;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Saying in words what the dashboard already worked out.
 *
 * <p>This is the clearest case in the product of the rule the whole design
 * rests on: <strong>AI explains, the backend computes.</strong> Every figure
 * quoted here was calculated by {@link InsightsService} in tested Java. The
 * model is handed those finished numbers and asked only to arrange them into
 * a sentence. It is never asked to add, compare, project or total anything,
 * because a model that is asked to do arithmetic will do it wrong occasionally
 * and confidently, and there is no way for the reader to tell which time.
 *
 * <p><strong>The guard.</strong> Even restricted to arranging, a model will
 * invent a figure — it writes "up 12%" because that is the shape of the
 * sentence, not because anything said 12. So the narration is checked before it
 * is returned: every number in it must be one of the numbers that went in. One
 * that is not means the whole narration is thrown away and the deterministic
 * sentence is used instead. This cannot catch a model that misattributes a real
 * figure to the wrong category, but it catches fabrication, which is the
 * failure that would put a number in front of somebody that exists nowhere in
 * their records.
 *
 * <p>The template is not a degraded mode. It is the default — AI is off unless
 * configured — so it is written to be worth reading on its own.
 */
@Component
public class SummaryNarrator {

    private static final Logger log = LoggerFactory.getLogger(SummaryNarrator.class);

    private static final String SYSTEM = """
            You write one short paragraph about a person's spending for a month.

            You are given figures that have already been calculated. Use only
            those figures. Never calculate, estimate, total or compare anything
            yourself. Never mention a number that is not in the figures given.

            Write 2 to 3 sentences, plain and direct, second person ("you").
            No greeting, no sign-off, no bullet points, no advice about
            budgeting unless the figures show something specific.
            If the month is quiet, say so briefly rather than padding.

            Reply with only: {"summary": "..."}
            """;

    /** Numbers at or below this are sentence structure, not money. */
    private static final int STRUCTURAL = 12;

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private final AiClient ai;

    public SummaryNarrator(AiClient ai) {
        this.ai = ai;
    }

    /**
     * @param source "template" when the sentence was assembled here, "ai" when
     *               a model wrote it — shown to the user, who is entitled to
     *               know which they are reading
     */
    public record Narration(String text, String source) {

        public static final String TEMPLATE = "template";
        public static final String AI = "ai";
    }

    public Narration narrate(UUID userId, Insights insights) {
        List<String> facts = facts(insights);
        String template = template(insights);

        if (!ai.isAvailable() || insights.totals().count() == 0) {
            return new Narration(template, Narration.TEMPLATE);
        }

        return fromModel(userId, facts)
                .map(text -> new Narration(text, Narration.AI))
                .orElseGet(() -> new Narration(template, Narration.TEMPLATE));
    }

    private Optional<String> fromModel(UUID userId, List<String> facts) {
        Optional<JsonNode> reply = ai.completeJson(userId, "insights-summary", SYSTEM,
                String.join("\n", facts));
        if (reply.isEmpty()) {
            return Optional.empty();
        }

        JsonNode summary = reply.get().path("summary");
        if (!summary.isTextual()) {
            return Optional.empty();
        }
        String text = summary.asText().strip();
        if (text.isEmpty() || text.length() > 600) {
            return Optional.empty();
        }

        if (!numbersAreReal(text, facts)) {
            log.warn("Discarded an AI summary containing a figure that was not in the data");
            return Optional.empty();
        }
        return Optional.of(text);
    }

    /**
     * Whether every figure in the narration came from the data.
     *
     * <p>Comparison is numeric rather than textual, because "12500.00" in the
     * facts and "12,500" in the sentence are the same number written two ways
     * and rejecting the second would fail every correct narration.
     *
     * <p>Small whole numbers are allowed through. They are how sentences are
     * built — "your three biggest categories", "the last 2 weeks" — and a
     * guard that rejected them would reject good writing far more often than
     * bad. The exemption stops at twelve, well below any amount of money worth
     * putting in a summary.
     */
    static boolean numbersAreReal(String text, List<String> facts) {
        Set<BigDecimal> allowed = new HashSet<>();
        for (String fact : facts) {
            Matcher factNumbers = NUMBER.matcher(fact);
            while (factNumbers.find()) {
                normalise(factNumbers.group()).ifPresent(allowed::add);
            }
        }

        Matcher found = NUMBER.matcher(text);
        while (found.find()) {
            Optional<BigDecimal> value = normalise(found.group());
            if (value.isEmpty()) {
                continue;
            }
            BigDecimal number = value.get();
            if (number.compareTo(BigDecimal.valueOf(STRUCTURAL)) <= 0
                    && number.stripTrailingZeros().scale() <= 0) {
                continue;
            }
            if (allowed.stream().noneMatch(a -> a.compareTo(number) == 0)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<BigDecimal> normalise(String raw) {
        try {
            return Optional.of(new BigDecimal(raw.replace(",", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * The figures, already calculated, written out for the model.
     *
     * <p>Rounded to whole units on the way in. The model can only quote what it
     * is given, so giving it paise would licence it to write them, and nobody
     * wants to read that their month came to ₹12,499.73.
     */
    List<String> facts(Insights insights) {
        String currency = insights.currency();
        List<String> facts = new ArrayList<>();

        facts.add("Month: " + insights.label());
        facts.add("Total spent: " + money(insights.totals().expense(), currency));
        facts.add("Number of payments: " + insights.totals().count());
        facts.add("Total received: " + money(insights.totals().income(), currency));

        if (insights.previous() != null && insights.previous().count() > 0) {
            facts.add("Spent in the same part of last month: "
                    + money(insights.previous().expense(), currency));
            if (insights.expenseChange() != null) {
                facts.add("Change in spending against last month: "
                        + money(insights.expenseChange().abs(), currency)
                        + (insights.expenseChange().signum() >= 0 ? " more" : " less"));
            }
        }

        if (insights.partial() && insights.projectedExpense() != null) {
            facts.add("Day " + insights.daysElapsed() + " of " + insights.daysInMonth());
            facts.add("Projected total for the whole month: "
                    + money(insights.projectedExpense(), currency));
        }

        for (CategorySlice slice : insights.categories().stream().limit(3).toList()) {
            facts.add("Category " + slice.name() + ": " + money(slice.amount(), currency)
                    + " across " + slice.count() + " payments");
        }

        for (CategorySlice mover : insights.movers().stream().limit(2).toList()) {
            if (mover.delta() != null && mover.delta().signum() != 0) {
                facts.add("Category " + mover.name() + " changed by "
                        + money(mover.delta().abs(), currency)
                        + (mover.delta().signum() > 0 ? " more" : " less")
                        + " than last month");
            }
        }

        if (!insights.merchants().isEmpty()) {
            MerchantSlice top = insights.merchants().get(0);
            facts.add("Most paid to " + top.name() + ": " + money(top.amount(), currency)
                    + " across " + top.count() + " payments");
        }

        if (insights.uncategorisedAmount() != null
                && insights.uncategorisedAmount().signum() > 0) {
            facts.add("Not yet categorised: "
                    + money(insights.uncategorisedAmount(), currency));
        }
        return facts;
    }

    /**
     * The sentence written without a model.
     *
     * <p>Assembled from the same figures in the same order of importance: what
     * was spent, how that compares, where it went, and what the month is headed
     * for. It says less than a good model would, and it is never wrong.
     */
    String template(Insights insights) {
        String currency = insights.currency();
        Totals totals = insights.totals();

        if (totals.count() == 0) {
            return insights.hasHistory()
                    ? "Nothing recorded for " + insights.label() + " yet."
                    : "Nothing here yet. Connect a mailbox or add a payment to get started.";
        }

        StringBuilder out = new StringBuilder();
        out.append("You spent ").append(money(totals.expense(), currency))
                .append(" across ").append(totals.count())
                .append(totals.count() == 1 ? " payment" : " payments")
                .append(insights.partial() ? " so far this month" : " in " + insights.label())
                .append('.');

        BigDecimal change = insights.expenseChange();
        if (change != null && change.signum() != 0 && insights.previous().count() > 0) {
            out.append(" That is ").append(money(change.abs(), currency))
                    .append(change.signum() > 0 ? " more" : " less")
                    .append(" than the same point last month.");
        }

        if (!insights.categories().isEmpty()) {
            CategorySlice top = insights.categories().get(0);
            out.append(' ').append(top.name()).append(" took the most, at ")
                    .append(money(top.amount(), currency)).append('.');
        }

        if (insights.partial() && insights.projectedExpense() != null) {
            out.append(" At this rate the month ends near ")
                    .append(money(insights.projectedExpense(), currency)).append('.');
        }

        if (insights.uncategorisedAmount() != null
                && insights.uncategorisedAmount().signum() > 0) {
            out.append(' ').append(money(insights.uncategorisedAmount(), currency))
                    .append(" is still uncategorised.");
        }
        return out.toString();
    }

    private static String money(BigDecimal amount, String currency) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        return Money.format(value.setScale(0, RoundingMode.HALF_UP), currency);
    }
}

