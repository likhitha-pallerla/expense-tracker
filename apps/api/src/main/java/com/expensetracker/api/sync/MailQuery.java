package com.expensetracker.api.sync;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides which mail is any of our business.
 *
 * <p>The connections page makes a promise in plain words: <em>only messages
 * that look like bank or payment alerts are read</em>. This class is where that
 * promise is either kept or quietly broken, so it is deliberately strict and
 * deliberately dull. Everything it lets through gets stored; everything else is
 * seen for a moment in memory and forgotten.
 *
 * <h2>Two gates, not one</h2>
 *
 * <p>Where the provider can filter for us, it should: Gmail's search runs on
 * Google's side, so a narrow {@code q} means a mailbox of 40,000 messages costs
 * one page of results instead of four hundred. Microsoft Graph's delta feed
 * accepts no such filter, so the same judgement has to be made here after
 * fetching. {@link #looksRelevant} is therefore the real gate and the Gmail
 * query is an optimisation — they are kept consistent so that a Gmail user and
 * an Outlook user end up with the same rows.
 *
 * <h2>Why it takes two signals</h2>
 *
 * <p>A single keyword is not enough. "Payment" appears in every invoice,
 * newsletter and subscription reminder ever sent. An amount alone appears in
 * every advertisement. Requiring <em>both</em> an amount and a payment word
 * removes almost all of that without needing a list of banks, which would go
 * stale the moment the user opens an account somewhere new.
 */
public final class MailQuery {

    private MailQuery() {
    }

    /** How far back a first sync reaches when nothing else is known. */
    public static final int DEFAULT_BACKFILL_DAYS = 90;

    /**
     * Words that appear in the alerts we want and rarely elsewhere.
     *
     * <p>Both Indian and generic wording, because the same person often has a
     * domestic bank and an international card.
     */
    private static final List<String> PAYMENT_WORDS = List.of(
            "debited", "credited", "debit", "credited to", "withdrawn",
            "transaction", "txn", "upi", "neft", "imps", "rtgs", "ach",
            "spent", "purchase", "paid", "payment", "charged", "refund",
            "e-mandate", "autopay", "auto-debit", "standing instruction",
            "statement", "minimum due", "bill", "emi", "atm");

    /**
     * A currency amount. Both the symbol and the abbreviations, because the
     * same bank uses different ones in the subject and the body, and HTML mail
     * often loses the symbol in transit.
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:₹|rs\\.?|inr|usd|eur|gbp|\\$|€|£)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?"
                    + "|[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*(?:₹|rs\\.?|inr|usd|eur|gbp)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Mail we never want, whatever else it says.
     *
     * <p>Marketing from a bank is still marketing, and "your statement is
     * ready, and here are five credit cards you could also have" would sail
     * through the two gates above. These are checked first.
     */
    private static final List<String> NEVER = List.of(
            "unsubscribe from these alerts",
            "you are receiving this promotional",
            "pre-approved",
            "pre approved",
            "congratulations! you are eligible",
            "apply now",
            "limited period offer",
            "lowest interest rate");

    /**
     * The search Gmail runs on its own side.
     *
     * <p>Deliberately broader than {@link #looksRelevant}: it is cheaper to
     * fetch a few messages we then discard than to write a query so clever that
     * it silently omits a bank whose wording we did not anticipate. Spam and
     * trash are excluded because a payment alert that landed there is one the
     * user has already told their provider they do not want.
     */
    public static String gmailQuery(Instant since) {
        long days = Math.max(1, ChronoUnit.DAYS.between(since, Instant.now()) + 1);
        return "-in:spam -in:trash newer_than:" + days + "d "
                + "(debited OR credited OR withdrawn OR \"transaction\" OR txn OR upi "
                + "OR neft OR imps OR rtgs OR spent OR purchase OR payment OR paid "
                + "OR charged OR refund OR statement OR \"minimum due\" OR emi OR atm)";
    }

    /**
     * Whether a fetched message is a payment alert.
     *
     * <p>Subject and body are judged together: plenty of alerts put the amount
     * in the subject and nothing but a link in the body, and plenty do the
     * opposite.
     */
    public static boolean looksRelevant(MailMessage message) {
        if (message == null || !message.hasContent()) {
            return false;
        }

        String haystack = ((message.subject() == null ? "" : message.subject())
                + " \n " + message.body()).toLowerCase(Locale.ROOT);

        for (String phrase : NEVER) {
            if (haystack.contains(phrase)) {
                return false;
            }
        }

        if (!AMOUNT.matcher(haystack).find()) {
            return false;
        }

        for (String word : PAYMENT_WORDS) {
            if (containsWord(haystack, word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whole-word containment.
     *
     * <p>{@code contains} would match "atm" inside "automatic" and "bill"
     * inside "billion", and an advertisement quoting a billion rupees would
     * become a transaction.
     */
    private static boolean containsWord(String haystack, String word) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            boolean startOk = at == 0 || !isWordChar(haystack.charAt(at - 1));
            int end = at + word.length();
            boolean endOk = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
