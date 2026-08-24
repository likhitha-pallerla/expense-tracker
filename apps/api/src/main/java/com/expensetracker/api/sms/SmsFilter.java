package com.expensetracker.api.sms;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a text message is a bank alert we are allowed to keep.
 *
 * <h2>Why this exists on the server at all</h2>
 *
 * <p>The Android app runs the same rules before anything leaves the handset,
 * and that is the version that actually protects the user: a message rejected
 * on-device never travels. So why repeat the work here?
 *
 * <p>Because the phone is not a trustworthy narrator. A stale build, a bug in
 * the native bridge, a tampered APK, or simply someone replaying a captured
 * request can all present this endpoint with whatever they like. If the server
 * accepted the client's judgement, a single mistake anywhere in that chain
 * would quietly write somebody's private conversations into a database — the
 * kind of failure that is discovered long after it matters, and can never be
 * undone by deleting rows. The device filter is an optimisation. This one is
 * the guarantee.
 *
 * <p>The two implementations are kept honest by a shared corpus of examples:
 * {@code sms-filter-vectors.json} is read by both the Java tests and the
 * TypeScript tests, so the pair cannot drift apart unnoticed.
 *
 * <h2>The shape of the judgement</h2>
 *
 * <p>Every other filter in this codebase leans towards keeping things, because
 * a missed transaction is a real loss. This one leans the other way. A bank
 * alert that slips through the net costs the user one manual entry. A private
 * message that slips through costs them their privacy, permanently. Those are
 * not comparable, so the tie-breaks all go the same way: when a message is
 * ambiguous, it stays on the phone.
 */
public final class SmsFilter {

    /**
     * Longest body we will look at.
     *
     * <p>A concatenated SMS tops out around 1,600 characters. Anything
     * substantially longer is not a text message, so rather than reason about
     * what it might be we decline to run regexes over it at all.
     */
    static final int MAX_BODY_LENGTH = 2_000;

    /**
     * A numeric sender at least this long is treated as a person.
     *
     * <p>Service short codes in India run to five or six digits. Mobile
     * numbers are ten. Nothing legitimate sits between them, so the boundary
     * is unambiguous and this is the single most effective rule here: it
     * removes every ordinary conversation in one step, whatever the words in
     * it happen to be.
     */
    static final int PERSONAL_NUMBER_LENGTH = 7;

    /**
     * An amount: a currency marker followed by digits.
     *
     * <p>The marker is required. Bare numbers appear in every message ever
     * sent — dates, counts, phone numbers, "reply 1 for yes" — and treating
     * them as money would drag the whole inbox in.
     */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:rs\\.?|inr|₹|usd|\\$|eur|€|gbp|£)\\s*[0-9][0-9,]*(?:\\.[0-9]{1,2})?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Money that has already moved.
     *
     * <p>All past tense, and that is deliberate rather than stylistic. "Spend
     * ₹2,000 and get ₹200 back" is an advertisement; "spent" is a receipt.
     * Requiring the completed form is what keeps marketing out without needing
     * a list of every promotion a bank has ever run.
     */
    private static final Pattern SETTLED_VERB = Pattern.compile(
            "\\b(?:debited|credited|deducted|spent|paid|withdrawn|withdrew|received"
                    + "|transferred|purchased|charged|refunded|reversed|sent"
                    + "|autopay|auto-debited|emi\\s+of|billed)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * One-time passwords.
     *
     * <p>These are rejected before anything else is considered, and the reason
     * is arithmetic rather than tidiness. "724193 is the OTP for a payment of
     * ₹2,500 to AMAZON" carries an amount and a verb, so it would otherwise be
     * stored — and then the real alert arrives moments later and the user is
     * charged ₹5,000 in their own records. Dropping every OTP costs nothing,
     * because an OTP has never been the only notice of a payment.
     */
    private static final Pattern OTP = Pattern.compile(
            "\\b(?:otp|o\\.t\\.p|one[\\s-]?time[\\s-]?(?:password|passcode|pin)"
                    + "|verification\\s+code|security\\s+code|auth(?:entication)?\\s+code"
                    + "|login\\s+code|2fa)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Attempts that moved no money.
     *
     * <p>A declined card still produces an alert shaped exactly like a
     * successful one. Recording it would invent an expense that never
     * happened, which is worse than missing a real one: the user has no
     * receipt to reconcile it against and no reason to doubt it.
     */
    private static final Pattern UNSUCCESSFUL = Pattern.compile(
            "\\b(?:declined|failed|unsuccessful|could\\s+not\\s+be\\s+processed"
                    + "|has\\s+been\\s+rejected|insufficient\\s+(?:funds|balance))\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Requests for money, which are not payments.
     *
     * <p>A UPI collect request says someone <em>wants</em> ₹500. Until it is
     * approved nothing has left the account, and if it is approved a separate
     * debit alert follows. Storing the request as well would double it.
     */
    private static final Pattern REQUEST = Pattern.compile(
            "\\b(?:has\\s+requested|is\\s+requesting|requesting\\s+(?:money|payment)"
                    + "|collect\\s+request|payment\\s+request|requested\\s+money)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Sales patter that survived the past-tense rule.
     *
     * <p>Deliberately short. The verb requirement already removes the vast
     * majority of promotions, and a long keyword list is a liability: every
     * entry is another chance to throw away a genuine alert because a bank
     * chose an unlucky word. These are the phrases that no transaction
     * notification has any reason to contain.
     */
    private static final Pattern PROMOTIONAL = Pattern.compile(
            "\\b(?:pre-?approved|apply\\s+now|click\\s+here|limited\\s+period"
                    + "|hurry|book\\s+now|shop\\s+now|t&c\\s+apply|unsubscribe"
                    + "|lowest\\s+interest|instant\\s+loan|win\\s+a)\\b",
            Pattern.CASE_INSENSITIVE);

    private SmsFilter() {
    }

    /**
     * Why a message was kept or dropped.
     *
     * <p>The reason travels back to the caller and is counted in the response.
     * Without it the app can only report "17 of 40 messages were skipped",
     * which tells a suspicious user nothing about what happened to the other
     * 23. With it, the settings screen can say plainly: none of them were
     * personal messages, they were OTPs and balance updates.
     */
    public record Decision(boolean accepted, Reason reason) {

        public static Decision accept() {
            return new Decision(true, Reason.ACCEPTED);
        }

        public static Decision reject(Reason reason) {
            return new Decision(false, reason);
        }
    }

    /** The distinct grounds on which a message can be dropped. */
    public enum Reason {
        ACCEPTED,
        /** Sender looks like a mobile number, so this is a conversation. */
        PERSONAL_SENDER,
        /** No sender at all; we cannot establish it is not a person. */
        UNKNOWN_SENDER,
        /** Empty, or far too long to be a text message. */
        MALFORMED,
        /** A one-time password, which duplicates a payment that follows. */
        OTP_CODE,
        /** An attempt that moved no money. */
        NOT_SETTLED,
        /** Someone asking to be paid, not a payment. */
        MONEY_REQUEST,
        /** Advertising. */
        PROMOTIONAL,
        /** No currency amount anywhere in the body. */
        NO_AMOUNT,
        /** An amount, but nothing saying money actually moved. */
        NO_TRANSACTION_VERB
    }

    /**
     * Judges one message.
     *
     * <p>The order of the checks matters. Cheap structural tests come first so
     * a personal conversation is dismissed on its sender alone, without its
     * contents ever being examined — the code should not read private messages
     * any more closely than it must. The exclusions then run ahead of the
     * inclusions, so that a message which looks like a payment <em>and</em>
     * like an OTP is treated as the OTP it is.
     */
    public static Decision check(String sender, String body) {
        if (body == null || body.isBlank() || body.length() > MAX_BODY_LENGTH) {
            return Decision.reject(Reason.MALFORMED);
        }
        if (sender == null || sender.isBlank()) {
            return Decision.reject(Reason.UNKNOWN_SENDER);
        }
        if (isPersonalNumber(sender)) {
            return Decision.reject(Reason.PERSONAL_SENDER);
        }

        String text = body.toLowerCase(Locale.ROOT);

        if (OTP.matcher(text).find()) {
            return Decision.reject(Reason.OTP_CODE);
        }
        if (UNSUCCESSFUL.matcher(text).find()) {
            return Decision.reject(Reason.NOT_SETTLED);
        }
        if (REQUEST.matcher(text).find()) {
            return Decision.reject(Reason.MONEY_REQUEST);
        }
        if (PROMOTIONAL.matcher(text).find()) {
            return Decision.reject(Reason.PROMOTIONAL);
        }
        if (!AMOUNT.matcher(text).find()) {
            return Decision.reject(Reason.NO_AMOUNT);
        }
        if (!SETTLED_VERB.matcher(text).find()) {
            return Decision.reject(Reason.NO_TRANSACTION_VERB);
        }
        return Decision.accept();
    }

    /**
     * True when the sender is a phone number rather than a business.
     *
     * <p>Indian bank alerts arrive from alphabetic sender IDs, usually with a
     * two-letter operator prefix: {@code AD-HDFCBK}, {@code VM-ICICIB},
     * {@code JD-SBIINB}. So the question asked here is deliberately the
     * crudest one available — <em>is there a letter anywhere?</em> — because
     * anything more specific is something to be evaded. An earlier version
     * matched the <em>shape</em> of a phone number instead, and a sender
     * written {@code (+91) 9812345678} slipped past it: the leading bracket
     * broke the pattern, the sender was taken for a business, and a private
     * message was read. Counting letters cannot fail that way, since no
     * arrangement of punctuation turns a number into a name.
     *
     * <p>Short numeric codes are allowed through because five- and six-digit
     * shortcodes are legitimately used for alerts; they still have to satisfy
     * every content rule below, so allowing them here concedes very little.
     */
    static boolean isPersonalNumber(String sender) {
        String trimmed = sender.trim();
        if (trimmed.chars().anyMatch(Character::isLetter)) {
            return false;
        }
        long digits = trimmed.chars().filter(Character::isDigit).count();
        return digits >= PERSONAL_NUMBER_LENGTH;
    }

    /** Every reason a message may be dropped, for reporting. */
    public static List<Reason> rejectionReasons() {
        return List.of(
                Reason.PERSONAL_SENDER,
                Reason.UNKNOWN_SENDER,
                Reason.MALFORMED,
                Reason.OTP_CODE,
                Reason.NOT_SETTLED,
                Reason.MONEY_REQUEST,
                Reason.PROMOTIONAL,
                Reason.NO_AMOUNT,
                Reason.NO_TRANSACTION_VERB);
    }
}
