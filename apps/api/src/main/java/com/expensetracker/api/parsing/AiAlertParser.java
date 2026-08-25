package com.expensetracker.api.parsing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.expensetracker.api.ai.AiClient;
import com.expensetracker.api.ai.AiProperties;
import tools.jackson.databind.JsonNode;

/**
 * The last resort for a message no rule could read.
 *
 * <p>This runs only after {@link AlertParser} has failed. That ordering is not
 * a performance detail — it is what keeps the system honest. A rule is a fact
 * about a bank's message format: it is exact, it is free, it works offline, and
 * when it is wrong somebody can read it and see why. A model is a guess. The
 * guess is worth having for the long tail of banks nobody has written a rule
 * for yet, and worth nothing at all if it is allowed to overrule a fact.
 *
 * <p><strong>Nothing the model says is taken at face value.</strong> Every
 * field comes back through the same deterministic checks the rules' output
 * would face: the amount is re-parsed by {@link Amounts}, the direction must be
 * one of exactly two words, the date must be a real date close to when the mail
 * arrived, and the whole answer is discarded unless the model's own confidence
 * clears {@link AiProperties#minConfidence()}. The model is being used as a
 * reader, not as an authority.
 *
 * <p>Everything it produces is marked {@code parsed_by = 'ai'} with the
 * confidence stored beside it, so a user can find every transaction that was
 * guessed at rather than read, and so a later rule can be checked against them.
 */
@Component
public class AiAlertParser {

    private static final Logger log = LoggerFactory.getLogger(AiAlertParser.class);

    /**
     * The instruction.
     *
     * <p>It insists on the refusal case first. A model asked to extract a
     * payment will find one in an OTP if nothing gives it permission to say no,
     * and an invented transaction is far worse than a message left unread —
     * the user would have to notice it to correct it, and the whole point of
     * this product is that they do not have to watch it.
     */
    private static final String SYSTEM = """
            You read one bank or payment notification and extract the payment.

            If the message is not about a completed payment -- if it is an OTP,
            a balance summary, a statement, a reminder, a promotion, a failed or
            declined transaction, or anything else -- reply exactly:
            {"payment": false}

            Otherwise reply with only a JSON object:
              payment     true
              amount      number, the amount of this payment, no symbols
              direction   "debit" if money left the account, "credit" if it arrived
              merchant    who was paid or who paid, or null
              last4       the last 4 digits of the account or card, or null
              date        ISO yyyy-mm-dd of the payment, or null
              confidence  0 to 1, how sure you are of the amount and direction

            Never use the account balance as the amount.
            Never invent a merchant. Use null when the message does not name one.
            """;

    /** Names the rule that did not exist, for the interface and for logs. */
    private static final String RULE_NAME = "Read by AI";

    private final AiClient ai;
    private final AiProperties properties;

    public AiAlertParser(AiClient ai, AiProperties properties) {
        this.ai = ai;
        this.properties = properties;
    }

    public boolean isAvailable() {
        return ai.isAvailable();
    }

    /** A reading, and how sure the model claimed to be of it. */
    public record Reading(ParsedAlert alert, double confidence) {
    }

    /**
     * Tries to read a message the rules could not.
     *
     * @param receivedAt when the mail arrived, used to sanity-check the date
     * @return a reading, or empty for every kind of failure and refusal
     */
    public Optional<Reading> read(UUID userId, String sender, String subject, String body,
            Instant receivedAt, ZoneId zone) {
        if (!ai.isAvailable()) {
            return Optional.empty();
        }

        String message = compose(sender, subject, body);
        if (message.isBlank()) {
            return Optional.empty();
        }

        Optional<JsonNode> reply = ai.completeJson(userId, "alert-fallback", SYSTEM, message);
        if (reply.isEmpty()) {
            return Optional.empty();
        }
        return validate(reply.get(), receivedAt, zone);
    }

    /**
     * Checks the model's answer against everything that can be checked.
     *
     * <p>Written as a sequence of rejections rather than a parse, because each
     * rejection is a thing that has to be true before somebody's money is
     * recorded, and they are easier to audit as a list than as a builder.
     */
    private Optional<Reading> validate(JsonNode node, Instant receivedAt, ZoneId zone) {
        if (!node.path("payment").asBoolean(false)) {
            // The model was given an explicit way to say "this is not a
            // payment" and used it. That is a correct answer, not a failure.
            return Optional.empty();
        }

        double confidence = node.path("confidence").asDouble(0);
        if (confidence < properties.minConfidence()) {
            log.debug("AI reading discarded: confidence {} below threshold", confidence);
            return Optional.empty();
        }

        BigDecimal amount = amountOf(node.path("amount"));
        if (amount == null) {
            return Optional.empty();
        }

        String direction = directionOf(node.path("direction"));
        if (direction == null) {
            // Half a payment is not a payment. Without a direction there is no
            // way to know whether this figure should be added or subtracted,
            // and defaulting would be a coin toss on somebody's balance.
            return Optional.empty();
        }

        Instant occurredAt = dateOf(node.path("date"), receivedAt, zone);
        boolean dateExact = occurredAt != null;

        return Optional.of(new Reading(
                new ParsedAlert(
                        null,
                        RULE_NAME,
                        amount,
                        direction,
                        merchantOf(node.path("merchant")),
                        last4Of(node.path("last4")),
                        dateExact ? occurredAt : receivedAt,
                        null,
                        dateExact,
                        null),
                confidence));
    }

    /**
     * Re-parses the amount rather than trusting the number.
     *
     * <p>Models return {@code 1250}, {@code "1250"}, {@code "1,250.00"} and
     * {@code "Rs. 1,250"} for the same field on the same day. Sending strings
     * through {@link Amounts} means lakh grouping means the same thing here as
     * everywhere else, and anything genuinely unreadable becomes no transaction
     * instead of a transaction for zero.
     */
    static BigDecimal amountOf(JsonNode node) {
        BigDecimal amount = null;
        if (node.isNumber()) {
            amount = node.decimalValue();
        } else if (node.isTextual()) {
            amount = Amounts.parseWithCurrency(node.asText()).orElse(null);
        }
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        return amount.compareTo(new BigDecimal("100000000")) > 0 ? null : amount;
    }

    /** Exactly two words are acceptable; there is no sensible third case. */
    static String directionOf(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText().strip().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "debit", "credit" -> value;
            default -> null;
        };
    }

    /**
     * A date only if it is plausibly this message's date.
     *
     * <p>A payment alert arrives within days of the payment. A model that
     * returns a date years away has misread a statement period, an expiry date
     * or a customer-since line, and the arrival time of the mail is a better
     * answer than a confident wrong one.
     */
    static Instant dateOf(JsonNode node, Instant receivedAt, ZoneId zone) {
        if (!node.isTextual() || receivedAt == null) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(node.asText().strip());
            Instant candidate = date.atStartOfDay(zone).toInstant();
            long days = ChronoUnit.DAYS.between(candidate, receivedAt);
            // Alerts can lag a few days; they never arrive before the payment
            // by more than a rounding of timezone.
            return days >= -2 && days <= 30 ? candidate : null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The merchant, if it is a name rather than a sentence.
     *
     * <p>A model that could not find a merchant sometimes explains itself in
     * the field instead of returning null — "The message does not name a
     * merchant". The distinguishing feature is not length but structure: a
     * merchant is a name, and names are short. "AMAZON PAY INDIA PRIVATE
     * LIMITED" is five words and about as long as they get; an explanation is
     * a clause and runs longer than that. Both limits are applied because
     * either one alone has an obvious counter-example.
     */
    static String merchantOf(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        String value = node.asText().strip();
        if (value.isEmpty() || "null".equalsIgnoreCase(value) || value.length() > 60) {
            return null;
        }
        return value.split("\\s+").length > 6 ? null : value;
    }

    /** Four digits or nothing; the account it selects must not be a guess. */
    static String last4Of(JsonNode node) {
        String value = node.isTextual() ? node.asText().strip()
                : node.isNumber() ? node.asText() : null;
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.length() == 4 ? digits : null;
    }

    /**
     * What the model is shown.
     *
     * <p>The sender is included because it is often the only thing identifying
     * the bank, and the body is capped because a marketing footer can be longer
     * than the alert and would cost more than the alert is worth to read. It is
     * scrubbed downstream by {@link AiClient}, not here — one place, so it
     * cannot be forgotten.
     */
    private static String compose(String sender, String subject, String body) {
        StringBuilder out = new StringBuilder();
        if (sender != null && !sender.isBlank()) {
            out.append("From: ").append(sender.strip()).append('\n');
        }
        if (subject != null && !subject.isBlank()) {
            out.append("Subject: ").append(subject.strip()).append('\n');
        }
        if (body != null && !body.isBlank()) {
            String text = body.strip();
            out.append(text.length() > 2000 ? text.substring(0, 2000) : text);
        }
        return out.toString().strip();
    }
}
