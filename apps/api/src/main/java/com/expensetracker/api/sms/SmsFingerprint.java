package com.expensetracker.api.sms;

import com.expensetracker.api.sync.BodyHash;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Fingerprints a text message for the {@code (user_id, body_hash)} constraint.
 *
 * <p>Mail is fingerprinted from its subject and body alone. Text messages need
 * one extra ingredient, because they are short enough to repeat exactly. Two
 * cups of tea bought from the same stall an hour apart can produce byte-identical
 * alerts when the bank's template carries no reference number:
 *
 * <pre>Rs 20 debited to VPA chaiwala@ybl</pre>
 *
 * <p>Hashing that body alone would store the first and silently discard the
 * second — and silent discard is the failure this codebase treats as worst,
 * because there is no error to notice and no way to tell afterwards that money
 * went missing from the record.
 *
 * <p>So the moment the message arrived is folded in. It is the one field that
 * genuinely distinguishes two otherwise identical alerts, and it is stable:
 * Android keeps the timestamp in the message row itself, so a rescan months
 * later — or after a backup and restore onto a new handset — reports the same
 * instant and produces the same hash. Duplicates therefore still collapse, while
 * distinct payments stay distinct.
 *
 * <h2>The rule this places on the client</h2>
 *
 * <p>The app must always send the timestamp <em>stored with the message</em>,
 * never {@code Date.now()}. A message captured live by the broadcast receiver
 * and the same message seen again by a later inbox scan have to agree to the
 * second, or the pair will hash differently and the user will see the payment
 * twice. This is the only place where the handset can create duplicates that
 * the database cannot catch, which is why it is spelled out here and enforced
 * by a test on the mobile side.
 */
public final class SmsFingerprint {

    private SmsFingerprint() {
    }

    /**
     * Builds the hash stored on {@code raw_messages.body_hash}.
     *
     * <p>Truncating to the second is deliberate. Nothing meaningful
     * distinguishes two alerts 40 milliseconds apart, but the sub-second part
     * of a timestamp is exactly the sort of value that survives one round trip
     * and not another.
     *
     * <p>When the arrival time is unknown the sender alone is used. That
     * degrades to the mail behaviour — identical bodies collapse into one — 
     * which is the safe direction to fail, since a timestamp invented here
     * would differ on every upload and duplicate the message endlessly.
     */
    public static String of(String sender, Instant receivedAt, String body) {
        String discriminator = receivedAt == null
                ? String.valueOf(sender)
                : sender + "|" + receivedAt.truncatedTo(ChronoUnit.SECONDS).getEpochSecond();

        // Reusing BodyHash keeps SMS and mail on one normalisation. If the way
        // whitespace or case is handled ever changes, it changes for both, and
        // a message forwarded from a phone to an inbox still collides.
        return BodyHash.of(discriminator, body);
    }
}
