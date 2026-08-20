package com.expensetracker.api.parsing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turning one payment alert into the facts of a payment.
 *
 * <p>Deliberately free of Spring and of the database: given a message and a
 * list of rules it returns what it found, which is what makes the awkward
 * shapes — a missing amount, a date read the wrong way round, a merchant that
 * runs into the next sentence — testable without a running system.
 *
 * <p>The order of the rules is the whole design. The most specific rule that
 * matches wins, and a user's own rule beats every built-in one; that is how
 * someone whose bank we have never seen fixes their own import without waiting
 * for a release.
 */
public final class AlertParser {

    /** Words a bank uses for money leaving. */
    private static final Set<String> OUT = Set.of(
            "debited", "debit", "paid", "sent", "spent", "used", "transferred",
            "withdrawn", "withdrawal", "deducted", "purchase");

    /** Words a bank uses for money arriving. */
    private static final Set<String> IN = Set.of(
            "credited", "credit", "received", "deposited", "refunded", "refund");

    /**
     * Anything longer is not a merchant name; it is a sentence the pattern ran
     * into. Storing it would pollute the merchant list permanently, and
     * merchants are shared across every report in the product.
     */
    private static final int MAX_MERCHANT = 60;

    private AlertParser() {
    }

    /**
     * @param sender     who sent the mail, used only by rules that restrict on it
     * @param subject    prepended to the body, because some banks put the whole
     *                   alert in the subject line and leave the body empty
     * @param receivedAt when the message arrived; both a sanity check on the
     *                   parsed date and the fallback when there isn't one
     */
    public static ParsedAlert parse(String sender, String subject, String body,
            Instant receivedAt, ZoneId zone, List<ParserRule> rules) {

        CharSequence text = new Bounded(join(subject, body));
        ParsedAlert nearMiss = null;

        for (ParserRule rule : rules) {
            if (!rule.appliesTo(sender, text)) {
                continue;
            }
            ParsedAlert parsed = apply(rule, text, receivedAt, zone);
            if (parsed.isSuccess()) {
                return parsed;
            }
            // A rule that matched but found nothing usable does not stop the
            // search: a specific rule recognising the shape of a message while
            // failing to read it must not block a vaguer rule that can. The
            // first such failure is remembered, because it names the rule that
            // came closest and that is the useful thing to tell the user.
            if (nearMiss == null) {
                nearMiss = parsed;
            }
        }
        return nearMiss != null ? nearMiss : ParsedAlert.noRule();
    }

    private static ParsedAlert apply(ParserRule rule, CharSequence text,
            Instant receivedAt, ZoneId zone) {

        String amountText = capture(rule, ParserRule.AMOUNT, text);
        Optional<BigDecimal> amount = Amounts.parse(amountText);
        String direction = direction(capture(rule, ParserRule.DIRECTION, text));

        List<String> missing = new ArrayList<>();
        if (amount.isEmpty()) {
            missing.add("amount");
        }
        if (direction == null) {
            missing.add("direction");
        }
        if (!missing.isEmpty()) {
            return ParsedAlert.incomplete(rule, missing);
        }

        String dateText = capture(rule, ParserRule.OCCURRED_AT, text);
        return new ParsedAlert(
                rule.id(),
                rule.name(),
                amount.get(),
                direction,
                merchant(capture(rule, ParserRule.MERCHANT, text)),
                last4(capture(rule, ParserRule.LAST4, text)),
                AlertDates.resolve(dateText, receivedAt, zone),
                reference(capture(rule, ParserRule.REFERENCE, text)),
                AlertDates.trusted(dateText, receivedAt, zone),
                null);
    }

    private static String capture(ParserRule rule, String field, CharSequence text) {
        Extractor extractor = rule.extractor(field);
        return extractor == null ? null : extractor.capture(text);
    }

    private static String join(String subject, String body) {
        String head = subject == null ? "" : subject.strip();
        String tail = body == null ? "" : body.strip();
        return head.isEmpty() ? tail : head + "\n" + tail;
    }

    /** @return "credit", "debit", or null when the word means neither. */
    static String direction(String word) {
        if (word == null) {
            return null;
        }
        String lower = word.strip().toLowerCase(Locale.ROOT);
        if (IN.contains(lower)) {
            return "credit";
        }
        return OUT.contains(lower) ? "debit" : null;
    }

    /**
     * Tidies a captured merchant, or rejects it.
     *
     * <p>A merchant is optional — a payment with no name is still a payment —
     * so anything doubtful is dropped rather than guessed at. A wrong merchant
     * is worse than none: it silently joins that payment to an unrelated group
     * in every report the user looks at.
     */
    static String merchant(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.strip()
                .replaceAll("\\s+", " ")
                .replaceAll("[\\s.,;:*-]+$", "")
                .strip();

        if (cleaned.length() < 2 || cleaned.length() > MAX_MERCHANT) {
            return null;
        }
        // A capture of pure punctuation or digits is the pattern having found
        // the wrong thing — an amount, a date, a reference number.
        return cleaned.matches(".*[A-Za-z].*") ? cleaned : null;
    }

    /** The last four digits of an account or card, or null. */
    static String last4(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        return digits.length() == 4 ? digits : null;
    }

    /**
     * A bank reference is decisive for deduplication, so a wrong one is
     * expensive: it would merge two unrelated payments. Anything that does not
     * look like a real reference is discarded.
     */
    static String reference(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.strip().toUpperCase(Locale.ROOT);
        if (!cleaned.matches("[A-Z0-9]{6,25}")) {
            return null;
        }
        // All the same character is a placeholder in a template, not a
        // reference: "XXXXXXXXXX" appears in more bank mail than you would hope.
        return cleaned.chars().distinct().count() > 1 ? cleaned : null;
    }
}
