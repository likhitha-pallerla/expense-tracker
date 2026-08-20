package com.expensetracker.api.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a message body into the fingerprint the database deduplicates on.
 *
 * <p>This is dedup layer L0: {@code raw_messages} has a unique constraint on
 * {@code (user_id, body_hash)}, so the same alert arriving twice — forwarded,
 * delivered to two linked mailboxes, or re-fetched after a cursor reset — can
 * only ever occupy one row. The check costs nothing at write time and cannot be
 * forgotten by a caller, which is exactly why it lives in the schema rather
 * than in a service.
 *
 * <h2>Why the normalisation is this timid</h2>
 *
 * <p>It is tempting to strip harder: remove digits, collapse currency symbols,
 * drop reference numbers. That would be a serious mistake here. Two genuine
 * purchases at the same shop for different amounts, or the same amount on
 * different days, differ <em>only</em> in the parts an aggressive normaliser
 * throws away. Over-normalising does not merge duplicates, it silently loses
 * real transactions, and the loss is invisible: there is no error, just money
 * that never appears.
 *
 * <p>So only genuinely meaningless variation is removed — how the transport
 * happened to wrap lines, trailing spaces, and letter case. Anything that could
 * conceivably distinguish two payments is kept. Near-duplicates that survive
 * this are caught later by the transaction-level dedup, which compares amount,
 * date and merchant with tolerances and can be reviewed by a human. L0 is the
 * layer that must never guess.
 */
public final class BodyHash {

    private BodyHash() {
    }

    /**
     * Fingerprints a message.
     *
     * <p>The subject is folded in as well as the body. Some banks send a body
     * so terse it is identical across alerts ("Transaction alert. View in the
     * app."), with everything that matters in the subject line; hashing the
     * body alone would treat a month of those as one message.
     */
    public static String of(String subject, String body) {
        String normalised = normalise(subject) + "\n" + normalise(body);
        return sha256(normalised);
    }

    /**
     * Removes variation that carries no meaning.
     *
     * <p>Non-breaking spaces are turned into ordinary ones first: they are
     * everywhere in HTML mail, they are invisible, and left alone they make two
     * identical messages hash differently depending on which template the bank
     * used that week.
     */
    static String normalise(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('\u00a0', ' ')
                .replace('\u200b', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM. If it is missing the platform
            // is broken in ways no fallback would survive.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
