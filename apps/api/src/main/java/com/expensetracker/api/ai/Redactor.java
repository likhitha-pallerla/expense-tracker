package com.expensetracker.api.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes what a language model has no business seeing.
 *
 * <p>Every other guard in this system protects the user from losing a
 * transaction. This one protects them from a bank alert being handed to a
 * company they have never heard of. Once text has been sent to a third-party
 * API it cannot be recalled, cannot be deleted from that provider's logs, and
 * may well have been trained on. So this runs on every string that leaves,
 * without exception and without a bypass flag.
 *
 * <h2>The tension this has to resolve</h2>
 *
 * <p>Redaction that removes everything is easy and useless: strip the digits
 * and the fallback parser has nothing left to read. What must survive is the
 * amount, the direction verb, the merchant, the last four digits of the account
 * and the payment reference. What must not survive is anything that identifies
 * the person or lets someone impersonate them.
 *
 * <p>The two overlap in one place, and it is worth being explicit about it
 * rather than pretending otherwise. <strong>A UPI reference and a bank account
 * number are both twelve-ish digit runs and cannot be told apart by shape.</strong>
 * The reference is needed — it is the strongest duplicate signal this system
 * has, the one that turns "probably the same payment" into "certainly". The
 * account number is exactly what should not leave.
 *
 * <p>So the distinction is drawn on the <em>label</em> rather than the shape: a
 * run introduced by "a/c", "account" or "card" is masked to its last four, and
 * a run introduced by "ref", "utr" or "rrn" is kept. An unlabelled run is kept,
 * because bank alerts reliably label the account and frequently do not label
 * the reference — so the opposite choice would lose the dedup signal on nearly
 * every message in exchange for guarding a number already printed on every
 * cheque the user has ever written.
 *
 * <p>Card numbers are treated far more harshly, in any position, labelled or
 * not. A full PAN is the one number in a payment alert that is worth something
 * on its own.
 */
public final class Redactor {

    private Redactor() {
    }

    /** Kept so the parser can still match a transaction to an account. */
    private static final int VISIBLE_DIGITS = 4;

    private static final String EMAIL = "[email]";
    private static final String PHONE = "[phone]";
    private static final String BALANCE = "[balance]";
    private static final String CODE = "[code]";

    /**
     * Runs of digits long enough to be a card, wherever they appear.
     *
     * <p>Allows spaces and hyphens between groups because that is how cards are
     * written. Thirteen digits is the shortest live scheme.
     */
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    /** A digit run introduced by something that calls it an account. */
    private static final Pattern LABELLED_ACCOUNT = Pattern.compile(
            "(?i)\\b(a/c(?:\\s*no\\.?)?|acct\\.?|account(?:\\s*no\\.?|\\s*number)?"
                    + "|card(?:\\s*no\\.?|\\s*ending)?)"
                    + "\\s*[:.#]?\\s*([xX*]*\\d[\\dxX*]{3,})");

    private static final Pattern EMAIL_ADDRESS = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    /**
     * A UPI address whose local part is a phone number.
     *
     * <p>The handle is kept because {@code @ybl} names a payment app, not a
     * person. The local part is not: {@code chaiwala@ybl} is a merchant and
     * must survive, {@code 9812345678@ybl} is somebody's mobile number.
     */
    private static final Pattern PHONE_VPA = Pattern.compile(
            "\\b(?:\\+?91)?[6-9]\\d{9}(@[A-Za-z]{2,})");

    /** Indian mobile numbers, with or without a country code. */
    private static final Pattern MOBILE = Pattern.compile(
            "(?<![\\dxX*@.])(?:\\+?91[ -]?)?[6-9]\\d{9}(?![\\d@])");

    /**
     * A balance, and only a balance.
     *
     * <p>Never needed to record a payment and never harmless: a running balance
     * is a direct statement of what somebody has. Matched by its label so the
     * amount of the payment itself — the one figure the parser exists to find —
     * is never touched.
     */
    private static final Pattern LABELLED_BALANCE = Pattern.compile(
            "(?i)\\b(avl\\.?\\s*(?:bal|balance)|available\\s+(?:bal|balance)"
                    + "|(?:closing|updated|current|a/c|acct|account)\\s+bal(?:ance)?"
                    + "|bal(?:ance)?)"
                    + "\\s*(?:is|of)?\\s*[:.]?\\s*"
                    + "(?:(?:inr|rs\\.?|₹)\\s*)?[\\d,]+(?:\\.\\d{1,2})?");

    /**
     * A one-time password and the words around it.
     *
     * <p>These should never reach here — {@code SmsFilter} rejects them well
     * upstream — but a code that has slipped one gate should not sail through
     * the next. Defence in depth costs one regex.
     */
    private static final Pattern OTP_BEFORE = Pattern.compile(
            "(?i)\\b(\\d{4,8})\\b(?=[^.]{0,40}?\\b(?:otp|one[- ]time|verification code)\\b)");
    private static final Pattern OTP_AFTER = Pattern.compile(
            "(?i)\\b(?:otp|one[- ]time password|verification code)\\b([^.\\d]{0,30})(\\d{4,8})\\b");

    /**
     * Cleans a string for sending to a model.
     *
     * <p>Order matters and is not arbitrary. Emails go first because they
     * contain runs that later patterns would otherwise mangle. Cards go before
     * accounts so a labelled card is caught by the harsher rule. Mobiles go
     * after UPI addresses so a phone-shaped VPA keeps its handle. Balances go
     * last, once no competing digits remain.
     */
    public static String scrub(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String out = text;
        out = EMAIL_ADDRESS.matcher(out).replaceAll(EMAIL);
        out = PHONE_VPA.matcher(out).replaceAll(PHONE + "$1");
        out = maskCards(out);
        out = maskLabelledAccounts(out);
        out = MOBILE.matcher(out).replaceAll(PHONE);
        out = OTP_BEFORE.matcher(out).replaceAll(CODE);
        out = OTP_AFTER.matcher(out).replaceAll(matchResult ->
                Matcher.quoteReplacement(matchResult.group().replace(matchResult.group(2), CODE)));
        out = maskBalances(out);
        return out;
    }

    private static String maskCards(String text) {
        Matcher matcher = CARD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(lastFour(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String maskLabelledAccounts(String text) {
        Matcher matcher = LABELLED_ACCOUNT.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = matcher.group(1) + " " + lastFour(matcher.group(2));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String maskBalances(String text) {
        Matcher matcher = LABELLED_BALANCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(matcher.group(1) + " " + BALANCE));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Reduces a number to the form a bank would have printed anyway.
     *
     * <p>Masking to the last four rather than removing the number outright is
     * what keeps account matching working: those four digits are how a
     * transaction finds the account it belongs to, and they are already on
     * every statement and receipt.
     */
    private static String lastFour(String raw) {
        String digits = raw.replaceAll("[^\\dxX*]", "");
        if (digits.length() <= VISIBLE_DIGITS) {
            return digits;
        }
        return "XX" + digits.substring(digits.length() - VISIBLE_DIGITS);
    }

    /**
     * Whether anything was removed.
     *
     * <p>Lets a log record that a message was cleaned without logging the
     * message.
     */
    public static boolean changed(String original, String scrubbed) {
        return original != null && !original.equals(scrubbed);
    }
}
