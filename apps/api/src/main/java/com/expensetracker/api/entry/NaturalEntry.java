package com.expensetracker.api.entry;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.expensetracker.api.parsing.Amounts;

/**
 * Reading "spent 850 on dinner at Zomato using HDFC card".
 *
 * <p>This is the feature usually built by sending the sentence to a language
 * model, and doing that first would be a mistake. The grammar people actually
 * use to record a payment is tiny — a verb, a number, and up to three
 * prepositional phrases — and it is a grammar, not a nuance. Written as rules
 * it answers instantly, costs nothing, works with no network and no API key,
 * gives the same answer every time, and can be tested exhaustively. A model
 * gives up all five of those properties to handle the same sentence.
 *
 * <p>So the model is the fallback, not the parser: {@link NaturalEntryService}
 * asks it only for the sentences these rules could not read at all. On the
 * evidence of the tests beside this class, that is a small minority.
 *
 * <h2>Order is load-bearing</h2>
 *
 * <p>Fields are pulled out and <em>removed</em> in a fixed order, each pass
 * narrowing what the next one can see. Dates come first, and that is not
 * arbitrary: in "paid 1200 on 12 Jan" a parser that looked for the amount first
 * would find 12 as readily as 1200. Taking the date out of the sentence before
 * looking for money makes the ambiguity impossible rather than unlikely.
 */
public final class NaturalEntry {

    private NaturalEntry() {
    }

    /** Longer than this is prose, not a payment note. */
    private static final int MAX_INPUT = 300;

    /**
     * Verbs that mean money arrived.
     *
     * <p>Debit is the default because it is overwhelmingly the common case, and
     * because the failure is asymmetric: an expense recorded as income
     * understates spending and flatters every chart, which is the direction a
     * budgeting tool must never be wrong in.
     */
    private static final Set<String> CREDIT_VERBS = Set.of(
            "received", "recieved", "got", "credited", "refund", "refunded",
            "earned", "salary", "income", "cashback", "reimbursed", "deposited");

    private static final Set<String> DEBIT_VERBS = Set.of(
            "spent", "paid", "pay", "bought", "buy", "debited", "gave",
            "purchased", "sent", "transferred", "withdrew");

    /** Words that end a captured phrase because they start a different one. */
    private static final Set<String> BOUNDARIES = Set.of(
            "at", "to", "from", "for", "on", "using", "via", "with", "by",
            "and", "in", "of", "yesterday", "today", "tomorrow");

    private static final Pattern CURRENCY_AMOUNT = Pattern.compile(
            "(?i)(?:₹|rs\\.?|inr)\\s*([\\d,]+(?:\\.\\d{1,2})?)"
                    + "|([\\d,]+(?:\\.\\d{1,2})?)\\s*(?:₹|rs\\.?|inr|rupees?)\\b");

    /**
     * A number on its own.
     *
     * <p>Rejects a number glued to letters, because "250g of beans", "5km",
     * "3pm" and "2bhk" are quantities and times rather than money, and reading
     * one as an amount produces a wrong transaction from a sentence that never
     * mentioned a figure. A space is what separates an amount from its
     * subject — "250 lunch" is money, "250g" is not.
     *
     * <p>A full stop that ends a sentence is allowed to follow, but a full stop
     * followed by digits is not: "spent 250." is two hundred and fifty, while
     * "250.50" must be matched whole rather than truncated to 250.
     */
    private static final Pattern BARE_AMOUNT = Pattern.compile(
            "(?<![\\d.,])([\\d,]+(?:\\.\\d{1,2})?)(?![\\d,a-zA-Z])(?!\\.\\d)");

    /**
     * The account, named after "using", "via" or "with".
     *
     * <p>Two things the obvious pattern gets wrong, both of them common ways to
     * say this. The words before the keyword are optional, so "using cash" and
     * "using card" work and not only "using hdfc card". And four digits may
     * follow the keyword, so "using card 4821" keeps the digits — which matter
     * more than the name, because two cards from the same bank are told apart
     * by nothing else.
     */
    private static final Pattern ACCOUNT_PHRASE = Pattern.compile(
            "(?i)\\b(?:using|via|thru|through|with|from)\\s+((?:\\w+\\s+){0,3}?"
                    + "(?:card|account|acct|wallet|upi|cash|a/c)"
                    + "(?:\\s+(?:no\\.?|number|ending(?:\\s+in)?|x+)?\\s*\\d{4})?)\\b");

    private static final Pattern MERCHANT_PHRASE = Pattern.compile(
            "(?i)\\b(?:at|to|from)\\s+(.+)$");

    private static final Pattern PURPOSE_PHRASE = Pattern.compile(
            "(?i)\\b(?:on|for)\\s+(.+)$");

    private static final Pattern DAYS_AGO = Pattern.compile(
            "(?i)\\b(\\d{1,3})\\s*days?\\s+ago\\b");

    private static final Pattern EXPLICIT_DATE = Pattern.compile(
            "(?i)\\b(?:on\\s+)?(\\d{1,2})(?:st|nd|rd|th)?[\\s/-]"
                    + "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"
                    + "(?:[\\s/-](\\d{2,4}))?\\b");

    private static final Pattern NUMERIC_DATE = Pattern.compile(
            "\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3),
            Map.entry("apr", 4), Map.entry("may", 5), Map.entry("jun", 6),
            Map.entry("jul", 7), Map.entry("aug", 8), Map.entry("sep", 9),
            Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    /**
     * Reads one sentence.
     *
     * @param today the user's today, in their zone — not the server's
     */
    public static EntryDraft parse(String input, LocalDate today) {
        if (input == null || input.isBlank()) {
            return EntryDraft.unreadable("Type what you spent, like \"250 lunch\".");
        }
        if (input.length() > MAX_INPUT) {
            return EntryDraft.unreadable("That is too long. Try a short note like \"250 lunch\".");
        }

        String text = input.strip().replaceAll("\\s+", " ");
        String direction = directionOf(text);

        Dated dated = extractDate(text, today);
        String rest = dated.remainder();

        Amount found = extractAmount(rest);
        if (found == null) {
            return EntryDraft.unreadable(
                    "No amount found. Try something like \"spent 250 on lunch\".");
        }
        rest = found.remainder();

        String accountHint = null;
        Phrase account = capture(rest, ACCOUNT_PHRASE, false);
        if (account != null) {
            accountHint = account.value();
            rest = remove(rest, account.start(), account.end());
        }

        String merchant = null;
        Phrase merchantPhrase = capture(rest, MERCHANT_PHRASE, true);
        if (merchantPhrase != null) {
            merchant = merchantPhrase.value();
            rest = remove(rest, merchantPhrase.start(), merchantPhrase.end());
        }

        String purpose = null;
        Phrase purposePhrase = capture(rest, PURPOSE_PHRASE, true);
        if (purposePhrase != null) {
            purpose = purposePhrase.value();
            rest = remove(rest, purposePhrase.start(), purposePhrase.end());
        }

        String leftover = tidy(stripVerbs(rest));

        // "500 groceries" names neither a merchant nor a purpose explicitly, so
        // whatever is left of the sentence is the best description available --
        // and is far more useful than filing it as "Payment".
        if (purpose == null && merchant == null && leftover != null) {
            purpose = leftover;
        }

        String description = purpose != null ? purpose : merchant;

        return new EntryDraft(
                found.value(),
                direction,
                merchant,
                description,
                accountHint,
                // The category is guessed from the same words that describe the
                // payment, and resolved against the user's own category names
                // later. Guessing a name here would invent categories they do
                // not have.
                purpose,
                dated.date(),
                dated.explicit(),
                EntryDraft.SOURCE_RULES,
                null);
    }

    /** A captured phrase and exactly the span it occupied. */
    private record Phrase(String value, int start, int end) {
    }

    /**
     * Pulls one prepositional phrase out, tracking precisely what it covered.
     *
     * <p>The span is tracked rather than recomputed because the patterns capture
     * to the end of the sentence and are then trimmed at the first boundary
     * word. Working the removal span back out by counting words afterwards is
     * the sort of thing that survives every test written for it and then
     * mangles a sentence with a double space in it.
     *
     * @param trimAtBoundary stop the phrase where the next one begins
     */
    private static Phrase capture(String text, Pattern pattern, boolean trimAtBoundary) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }

        String captured = matcher.group(1);
        String kept = trimAtBoundary ? untilBoundary(captured) : captured;
        String value = tidy(kept);
        if (value == null) {
            return null;
        }

        int captureStart = matcher.start(1);
        return new Phrase(value, matcher.start(), captureStart + kept.stripTrailing().length());
    }

    private static String directionOf(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : lower.split("[^a-z]+")) {
            if (CREDIT_VERBS.contains(word)) {
                return "credit";
            }
            if (DEBIT_VERBS.contains(word)) {
                return "debit";
            }
        }
        return "debit";
    }

    private record Amount(BigDecimal value, String remainder) {
    }

    /**
     * Finds the money.
     *
     * <p>A figure carrying a currency marker wins outright, wherever it sits. In
     * "2 coffees for rs 250" both numbers are plausible by shape and only the
     * marker separates them.
     */
    private static Amount extractAmount(String text) {
        Matcher marked = CURRENCY_AMOUNT.matcher(text);
        if (marked.find()) {
            String digits = marked.group(1) != null ? marked.group(1) : marked.group(2);
            Optional<BigDecimal> value = Amounts.parse(digits);
            if (value.isPresent()) {
                return new Amount(value.get(), remove(text, marked.start(), marked.end()));
            }
        }

        Matcher bare = BARE_AMOUNT.matcher(text);
        while (bare.find()) {
            Optional<BigDecimal> value = Amounts.parse(bare.group(1));
            if (value.isPresent()) {
                return new Amount(value.get(), remove(text, bare.start(), bare.end()));
            }
        }
        return null;
    }

    private record Dated(LocalDate date, boolean explicit, String remainder) {
    }

    /**
     * Finds when, and takes it out of the sentence.
     *
     * <p>A date in the future is read as the same day last year rather than
     * accepted. Nobody records a payment that has not happened yet, so "on 12
     * Dec" typed in January means last December — and booking it eleven months
     * ahead would put it beyond every report the user looks at.
     */
    private static Dated extractDate(String text, LocalDate today) {
        Matcher daysAgo = DAYS_AGO.matcher(text);
        if (daysAgo.find()) {
            int days = Integer.parseInt(daysAgo.group(1));
            if (days <= 366) {
                return new Dated(today.minusDays(days), true,
                        remove(text, daysAgo.start(), daysAgo.end()));
            }
        }

        Matcher explicit = EXPLICIT_DATE.matcher(text);
        if (explicit.find()) {
            LocalDate parsed = fromParts(explicit.group(1), explicit.group(2),
                    explicit.group(3), today);
            if (parsed != null) {
                return new Dated(parsed, true, remove(text, explicit.start(), explicit.end()));
            }
        }

        Matcher numeric = NUMERIC_DATE.matcher(text);
        if (numeric.find()) {
            LocalDate parsed = fromNumeric(numeric.group(1), numeric.group(2),
                    numeric.group(3), today);
            if (parsed != null) {
                return new Dated(parsed, true, remove(text, numeric.start(), numeric.end()));
            }
        }

        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("day before yesterday")) {
            return new Dated(today.minusDays(2), true,
                    replaceOnce(text, "day before yesterday"));
        }
        if (lower.contains("yesterday")) {
            return new Dated(today.minusDays(1), true, replaceOnce(text, "yesterday"));
        }
        if (lower.contains("today")) {
            return new Dated(today, true, replaceOnce(text, "today"));
        }

        for (DayOfWeek day : DayOfWeek.values()) {
            String name = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (lower.contains(name)) {
                return new Dated(lastOccurrenceOf(day, today), true,
                        replaceOnce(text, "last " + name).replaceAll("(?i)\\b" + name + "\\b", " "));
            }
        }

        return new Dated(today, false, text);
    }

    private static LocalDate fromParts(String day, String month, String year, LocalDate today) {
        Integer monthNumber = MONTHS.get(month.toLowerCase(Locale.ROOT));
        if (monthNumber == null) {
            return null;
        }
        return build(Integer.parseInt(day), monthNumber, year, today);
    }

    /**
     * Day first, always.
     *
     * <p>{@code 03/04} is the third of April here, not the fourth of March.
     * There is no way to tell them apart and no neutral choice, so it follows
     * the convention of the region this is built for, and every other date in
     * the product is displayed the same way.
     */
    private static LocalDate fromNumeric(String first, String second, String year, LocalDate today) {
        int day = Integer.parseInt(first);
        int month = Integer.parseInt(second);
        if (month > 12 && day <= 12) {
            int swap = day;
            day = month;
            month = swap;
        }
        return build(day, month, year, today);
    }

    private static LocalDate build(int day, int month, String year, LocalDate today) {
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            return null;
        }
        try {
            int resolvedYear = today.getYear();
            if (year != null) {
                int parsed = Integer.parseInt(year);
                resolvedYear = parsed < 100 ? 2000 + parsed : parsed;
            }
            LocalDate date = LocalDate.of(resolvedYear, month, day);
            return year == null && date.isAfter(today) ? date.minusYears(1) : date;
        } catch (java.time.DateTimeException e) {
            // 31 February and similar. Silently dropping the date is right:
            // the amount is still usable and the user can fix the date on the
            // confirmation form.
            return null;
        }
    }

    private static LocalDate lastOccurrenceOf(DayOfWeek day, LocalDate today) {
        LocalDate candidate = today;
        for (int i = 0; i < 7; i++) {
            candidate = candidate.minusDays(1);
            if (candidate.getDayOfWeek() == day) {
                return candidate;
            }
        }
        return today;
    }

    /** Stops a captured phrase at the word that begins the next one. */
    private static String untilBoundary(String phrase) {
        StringBuilder out = new StringBuilder();
        for (String word : phrase.split(" ")) {
            if (BOUNDARIES.contains(word.toLowerCase(Locale.ROOT))) {
                break;
            }
            out.append(word).append(' ');
        }
        return out.toString();
    }

    private static String stripVerbs(String text) {
        StringBuilder out = new StringBuilder();
        for (String word : text.split(" ")) {
            String lower = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (lower.isEmpty() || CREDIT_VERBS.contains(lower) || DEBIT_VERBS.contains(lower)
                    || BOUNDARIES.contains(lower)) {
                continue;
            }
            out.append(word).append(' ');
        }
        return out.toString();
    }

    private static String remove(String text, int start, int end) {
        int safeEnd = Math.min(end, text.length());
        return (text.substring(0, start) + " " + text.substring(safeEnd)).replaceAll("\\s+", " ");
    }

    private static String replaceOnce(String text, String phrase) {
        return text.replaceAll("(?i)\\b" + Pattern.quote(phrase) + "\\b", " ")
                .replaceAll("\\s+", " ");
    }

    private static String tidy(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.strip().replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
                .replaceAll("\\s+", " ");
        return cleaned.isBlank() ? null : cleaned;
    }
}
