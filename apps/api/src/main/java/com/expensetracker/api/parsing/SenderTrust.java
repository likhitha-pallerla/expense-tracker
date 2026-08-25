package com.expensetracker.api.parsing;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a message's sender is one this ledger should accept money
 * from.
 *
 * <p><strong>Why this exists.</strong> A parser that reads any message
 * containing an amount and the word "debited" will happily read one an attacker
 * sent. Email addresses are not secret, the From header is not authenticated,
 * and the result is not a spam message the user can ignore — it is a row in
 * their transaction history, with an attacker-chosen description sitting in a
 * screen they trust, distorting every budget and forecast built on top of it.
 * The SMS path has always gated on sender; mail did not, and this closes that.
 *
 * <p><strong>The failure mode is deliberate.</strong> An unrecognised sender
 * does not have its message discarded — it is quarantined, shown to the user,
 * and released with one click that also remembers the decision. Refusing a real
 * bank costs a click. Accepting a forged alert costs a wrong number in
 * someone's records that they may never notice. Those are not comparable, so
 * the default is to refuse.
 *
 * <p>Nothing here is a substitute for verifying DKIM, which is the real answer
 * and needs message headers this application does not yet fetch. This is the
 * part that can be built and tested today.
 */
public final class SenderTrust {

    private SenderTrust() {
    }

    /**
     * Domains that are never trusted, whatever the user says.
     *
     * <p>Trusting a consumer mail provider would mean trusting every one of its
     * users, so "trust this sender" is not offered for these — the request is
     * refused rather than obeyed. A bank does not send alerts from a free
     * webmail account; anything that appears to is either a forwarding rule or
     * a forgery, and neither should write to a ledger unattended.
     */
    private static final Set<String> NEVER_TRUSTED = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "ymail.com",
            "outlook.com", "hotmail.com", "live.com", "msn.com",
            "icloud.com", "me.com", "mac.com",
            "proton.me", "protonmail.com", "pm.me",
            "aol.com", "zoho.com", "gmx.com", "mail.com", "yandex.com",
            "rediffmail.com", "sify.com",
            "example.com", "example.org", "example.net", "localhost");

    /**
     * Institutions recognised without the user having to say so.
     *
     * <p>A convenience, not a security boundary: everything absent from it is
     * quarantined rather than dropped, so a bank that is missing costs one
     * click. Kept short and obvious on purpose — a long list assembled from
     * guesswork would be a list of domains nobody verified.
     */
    private static final Set<String> KNOWN_INSTITUTIONS = Set.of(
            // Indian banks
            "hdfcbank.net", "hdfcbank.com", "icicibank.com", "axisbank.com",
            "sbi.co.in", "onlinesbi.sbi", "kotak.com", "yesbank.in",
            "indusind.com", "idfcfirstbank.com", "federalbank.co.in",
            "rblbank.com", "bandhanbank.com", "aubank.in", "pnb.co.in",
            "bankofbaroda.co.in", "canarabank.com", "unionbankofindia.co.in",
            "idbibank.co.in", "centralbank.co.in", "indianbank.co.in",
            "citibank.co.in", "hsbc.co.in", "sc.com", "dbs.com",
            // Payments and cards
            "paytm.com", "paytmbank.com", "phonepe.com", "amazonpay.in",
            "razorpay.com", "billdesk.com", "cred.club", "mobikwik.com",
            "payu.in", "cashfree.com", "juspay.in",
            "americanexpress.com", "aexp.com", "visa.com", "mastercard.com",
            // Common merchants that send genuine payment receipts
            "amazon.in", "amazon.com", "flipkart.com", "swiggy.in", "zomato.com",
            "uber.com", "olacabs.com", "irctc.co.in", "myntra.com",
            "netflix.com", "spotify.com", "apple.com", "google.com");

    /** Why a message was accepted or held back. */
    public enum Verdict {
        /** Sender is a recognised institution. */
        KNOWN_INSTITUTION,
        /** The user has trusted this domain themselves. */
        TRUSTED_BY_USER,
        /** Not recognised: hold for the user to look at. */
        UNRECOGNISED,
        /** A consumer mail provider, or no usable domain at all. */
        NOT_AN_INSTITUTION;

        public boolean isAccepted() {
            return this == KNOWN_INSTITUTION || this == TRUSTED_BY_USER;
        }
    }

    /**
     * Judges one sender.
     *
     * @param sender          the From header, bare or in "Name &lt;a@b&gt;" form
     * @param trustedByUser   domains this user has already accepted
     */
    public static Verdict judge(String sender, Set<String> trustedByUser) {
        Optional<String> parsed = domainOf(sender);
        if (parsed.isEmpty()) {
            return Verdict.NOT_AN_INSTITUTION;
        }

        String domain = parsed.get();
        if (isNeverTrusted(domain)) {
            return Verdict.NOT_AN_INSTITUTION;
        }
        if (matchesAny(domain, KNOWN_INSTITUTIONS)) {
            return Verdict.KNOWN_INSTITUTION;
        }
        if (trustedByUser != null && matchesAny(domain, trustedByUser)) {
            return Verdict.TRUSTED_BY_USER;
        }
        return Verdict.UNRECOGNISED;
    }

    /** Whether a domain may be added to a user's trusted list at all. */
    public static boolean canBeTrusted(String domain) {
        return domainOf(domain).filter(d -> !isNeverTrusted(d)).isPresent();
    }

    /**
     * The domain part of a sender, lowercased.
     *
     * <p>Accepts either a bare address or the display form. Where a header
     * contains several addresses — which a forged one may, precisely to confuse
     * a parser like this — the one inside angle brackets wins, because that is
     * the one a mail client shows and the user believes.
     */
    public static Optional<String> domainOf(String sender) {
        if (sender == null || sender.isBlank()) {
            return Optional.empty();
        }

        String value = sender.strip();
        int open = value.lastIndexOf('<');
        int close = value.lastIndexOf('>');
        if (open >= 0 && close > open) {
            value = value.substring(open + 1, close).strip();
        }

        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }

        value = value.strip().toLowerCase(Locale.ROOT);
        // A trailing dot is a valid fully-qualified form and would otherwise
        // make "hdfcbank.net." a different domain from "hdfcbank.net".
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }

        if (value.isBlank() || !value.contains(".")) {
            return Optional.empty();
        }

        // Anything outside the ASCII host alphabet is refused rather than
        // normalised. Homograph domains — a Cyrillic "а" inside what reads as
        // "hdfcbank.net" — exist to be mistaken for the real thing by exactly
        // the kind of comparison happening below.
        if (!value.matches("[a-z0-9.-]+")) {
            return Optional.empty();
        }

        return Optional.of(value);
    }

    private static boolean isNeverTrusted(String domain) {
        return matchesAny(domain, NEVER_TRUSTED);
    }

    /**
     * Whether a domain is, or sits under, one of a set.
     *
     * <p>The dot matters. Banks send from subdomains — {@code
     * alerts.hdfcbank.net}, {@code emailer.icicibank.com} — so a plain equality
     * check would reject genuine mail. But a plain {@code endsWith} would
     * accept {@code myhdfcbank.net} and {@code hdfcbank.net.attacker.io},
     * which is the whole attack. Only a match on a label boundary counts.
     */
    private static boolean matchesAny(String domain, Set<String> candidates) {
        for (String candidate : candidates) {
            if (domain.equals(candidate) || domain.endsWith("." + candidate)) {
                return true;
            }
        }
        return false;
    }

    /** The built-in list, for showing the user what is recognised already. */
    public static List<String> knownInstitutions() {
        return KNOWN_INSTITUTIONS.stream().sorted().toList();
    }
}
