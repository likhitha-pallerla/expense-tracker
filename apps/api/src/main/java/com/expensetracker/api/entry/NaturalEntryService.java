package com.expensetracker.api.entry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.expensetracker.api.ai.AiClient;
import com.expensetracker.api.parsing.Amounts;
import com.expensetracker.api.profile.UserSettings;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turning a typed sentence into something the user can confirm.
 *
 * <p>Two things happen here that {@link NaturalEntry} cannot do on its own.
 *
 * <p>First, the hints are <em>resolved</em>. The parser produces the words the
 * user typed — "hdfc card", "dinner" — and this matches them against the
 * accounts and categories that actually exist for this user. Resolving here
 * rather than in the parser keeps the parser pure and testable without a
 * database, and keeps this the only place that needs to know that a person may
 * have two cards from the same bank.
 *
 * <p>Second, the model is asked only when the rules come back empty-handed.
 * That ordering is the whole design: the rules answer in microseconds, cost
 * nothing, work with no API key and give the same answer every time. A model
 * gives up all four for the same sentence. It earns its place only on the
 * sentences the rules genuinely cannot read.
 */
@Service
public class NaturalEntryService {

    private static final Logger log = LoggerFactory.getLogger(NaturalEntryService.class);

    /**
     * What the model is allowed to do, stated as narrowly as possible.
     *
     * <p>It is told to return nulls rather than guess, because a wrong merchant
     * on a pre-filled form is harder to notice than an empty field.
     */
    private static final String SYSTEM = """
            You extract one payment from a short sentence a person typed.
            Reply with only a JSON object, with these keys:
              amount      number, required, positive, no currency symbol
              direction   "debit" if money was spent, "credit" if received
              merchant    who was paid, or null
              description what it was for, or null
              account     the card, bank or wallet named, or null
              date        ISO yyyy-mm-dd, or null if the sentence gives no date
            Use null for anything the sentence does not say. Do not guess.
            If there is no amount, reply {"amount": null}.
            """;

    private final JdbcTemplate jdbc;
    private final AiClient ai;
    private final UserSettings settings;

    public NaturalEntryService(JdbcTemplate jdbc, AiClient ai, UserSettings settings) {
        this.jdbc = jdbc;
        this.ai = ai;
        this.settings = settings;
    }

    /**
     * Reads one sentence and returns a draft, resolved against the user's data.
     *
     * <p>Never writes anything. The caller shows the result and the user
     * confirms it.
     */
    public EntrySuggestion suggest(UUID userId, String text) {
        ZoneId zone = settings.zoneOf(userId);
        LocalDate today = LocalDate.now(zone);

        EntryDraft draft = NaturalEntry.parse(text, today);

        if (!draft.isSuccess() && ai.isAvailable() && worthAsking(text)) {
            draft = askModel(userId, text, today).orElse(draft);
        }

        if (!draft.isSuccess()) {
            return EntrySuggestion.unreadable(draft.problem(), draft.source());
        }
        return resolve(userId, draft);
    }

    /**
     * Whether a sentence is worth spending a call on.
     *
     * <p>The rules fail on two very different inputs: an unusual sentence that
     * does contain a payment, and a blank box. Only the first is worth money.
     * A sentence with no digit anywhere in it has no amount for any reader to
     * find, and asking about it burns quota to be told so.
     */
    private static boolean worthAsking(String text) {
        return text != null && text.length() <= 300 && text.chars().anyMatch(Character::isDigit);
    }

    /**
     * The model's answer, re-checked field by field.
     *
     * <p>Nothing it returns is trusted as given. The amount goes back through
     * the same {@link Amounts} parser the rest of the system uses, the
     * direction must be one of two words, and the date must be a real date
     * that is not in the future. A model that returns {@code "amount": "about
     * 500"} produces no draft rather than a transaction for zero.
     */
    private Optional<EntryDraft> askModel(UUID userId, String text, LocalDate today) {
        Optional<JsonNode> reply = ai.completeJson(userId, "natural-entry", SYSTEM, text);
        if (reply.isEmpty()) {
            return Optional.empty();
        }
        JsonNode node = reply.get();

        BigDecimal amount = amountOf(node.path("amount"));
        if (amount == null) {
            return Optional.empty();
        }

        String direction = "credit".equals(node.path("direction").asText(null))
                ? "credit"
                : "debit";

        LocalDate date = dateOf(node.path("date"), today);

        log.debug("Model read a sentence the rules could not");
        return Optional.of(new EntryDraft(
                amount,
                direction,
                textOf(node.path("merchant")),
                textOf(node.path("description")),
                textOf(node.path("account")),
                textOf(node.path("description")),
                date == null ? today : date,
                date != null,
                EntryDraft.SOURCE_AI,
                null));
    }

    /**
     * Accepts a number or a string, because models return both for the same
     * field, and runs strings through the shared amount parser so that
     * "1,20,000" means what it means everywhere else in this codebase.
     */
    static BigDecimal amountOf(JsonNode node) {
        BigDecimal value = null;
        if (node.isNumber()) {
            value = node.decimalValue();
        } else if (node.isTextual()) {
            value = Amounts.parseWithCurrency(node.asText()).orElse(null);
        }
        if (value == null || value.signum() <= 0) {
            return null;
        }
        // A model that hallucinates a figure tends to hallucinate a huge one.
        return value.compareTo(new BigDecimal("100000000")) > 0 ? null : value;
    }

    /** A real date, no later than today; anything else is treated as absent. */
    static LocalDate dateOf(JsonNode node, LocalDate today) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            LocalDate parsed = LocalDate.parse(node.asText().strip());
            return parsed.isAfter(today) ? null : parsed;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    private static String textOf(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText().strip();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : trim(value);
    }

    /** Model output goes on a form, so it is capped at a form-sized length. */
    private static String trim(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120).strip();
    }

    /** One row of something the user could have meant. */
    private record Named(UUID id, String name, String extra) {
    }

    private EntrySuggestion resolve(UUID userId, EntryDraft draft) {
        Named account = matchAccount(userId, draft.accountHint());
        Named category = matchCategory(userId, draft.categoryHint());

        return new EntrySuggestion(
                draft.amount(),
                draft.direction(),
                draft.merchant(),
                draft.description(),
                draft.occurredOn(),
                draft.dateExplicit(),
                account == null ? null : account.id(),
                account == null ? null : account.name(),
                category == null ? null : category.id(),
                category == null ? null : category.name(),
                draft.accountHint(),
                draft.categoryHint(),
                draft.understood(),
                draft.source(),
                null);
    }

    /**
     * Finds the account the words meant.
     *
     * <p>"hdfc card" will not equal "HDFC Millennia Credit Card" under any
     * amount of normalising, so this scores instead: a word of the hint that
     * appears in the account's name is a point, the last four digits are worth
     * more than a word, and a single clear winner is required. Two accounts
     * scoring the same means the sentence was ambiguous — "using hdfc" with two
     * HDFC accounts — and leaving it unresolved puts the choice in front of the
     * user, where it belongs.
     */
    private Named matchAccount(UUID userId, String hint) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        List<Named> accounts = jdbc.query("""
                select id, name, coalesce(last4, '') as extra
                from accounts
                where user_id = ? and is_archived = false
                """,
                (rs, row) -> new Named(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("extra")),
                userId);
        return bestMatch(accounts, hint, true);
    }

    private Named matchCategory(UUID userId, String hint) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        List<Named> categories = jdbc.query("""
                select id, name, '' as extra from categories where user_id = ?
                """,
                (rs, row) -> new Named(rs.getObject("id", UUID.class),
                        rs.getString("name"), rs.getString("extra")),
                userId);
        return bestMatch(categories, hint, false);
    }

    private static Named bestMatch(List<Named> candidates, String hint, boolean useDigits) {
        String needle = hint.toLowerCase(Locale.ROOT);
        String[] words = needle.split("[^a-z0-9]+");

        Named best = null;
        int bestScore = 0;
        boolean tied = false;

        for (Named candidate : candidates) {
            int score = score(candidate, words, useDigits);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                tied = false;
            } else if (score == bestScore && score > 0) {
                tied = true;
            }
        }
        return tied || bestScore == 0 ? null : best;
    }

    private static int score(Named candidate, String[] words, boolean useDigits) {
        String name = candidate.name().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String word : words) {
            if (word.length() < 2) {
                continue;
            }
            if (useDigits && word.length() == 4 && word.chars().allMatch(Character::isDigit)) {
                if (word.equals(candidate.extra())) {
                    // The digits identify one card and nothing else does.
                    score += 5;
                }
                continue;
            }
            if (name.contains(word)) {
                score += 2;
            }
        }
        return score;
    }
}
