package com.expensetracker.api.sms;

import java.time.Instant;
import java.util.List;

/**
 * One upload from a handset.
 *
 * <p>Batched rather than one request per message, because a first scan of an
 * inbox can turn up several hundred alerts and a phone on a train will not
 * survive that many round trips. The batch is also the unit the app retries:
 * it may be sent again in full after a dropped connection, and the database
 * constraints make that harmless.
 *
 * @param deviceId    stable identifier for the handset, so a user with a phone
 *                    and a tablet gets one {@code source_connection} each and
 *                    can revoke either. Generated once by the app and kept in
 *                    secure storage; it is not a hardware serial, which we have
 *                    no business collecting.
 * @param deviceName  what to show in the connections list, e.g. "Pixel 8".
 * @param messages    the candidates, already filtered on-device. The server
 *                    filters them again regardless.
 */
public record SmsBatchRequest(String deviceId, String deviceName, List<SmsMessageRequest> messages) {

    /** Largest batch we will accept in one request. */
    public static final int MAX_MESSAGES = 200;

    public List<SmsMessageRequest> safeMessages() {
        return messages == null ? List.of() : messages;
    }

    /**
     * One text message as the app reports it.
     *
     * <p>Note what is absent: no thread id, no contact name, no Android row id.
     * The endpoint asks for the least it can work with, so that a future bug in
     * the app cannot leak a field the server never wanted. The Android row id in
     * particular is deliberately not collected — it is unstable across a backup
     * and restore, so it would be useless for deduplication while still being a
     * handle into the user's private message store.
     *
     * @param sender     the originating address, as the network supplied it.
     * @param body       the full text.
     * @param receivedAt when the message arrived, taken from the message record
     *                   itself — never from the phone's current clock. See
     *                   {@link SmsFingerprint}.
     */
    public record SmsMessageRequest(String sender, String body, Instant receivedAt) {
    }
}
