package com.expensetracker.api.sms;

import com.expensetracker.api.parsing.ParseResult;
import com.expensetracker.api.parsing.ParseService;
import com.expensetracker.api.sms.SmsBatchRequest.SmsMessageRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes text messages from a handset and puts the ones that are bank alerts
 * into the same queue that mail arrives in.
 *
 * <p>Everything downstream — parsing, duplicate detection, categorisation — is
 * shared with e-mail and needs no knowledge that a message came from a phone.
 * That is the whole design: this class ends at {@code raw_messages}, and the
 * seeded parser rules already carry a null {@code provider}, so the UPI, card
 * and ATM patterns written for e-mail alerts read SMS unchanged.
 */
@Service
public class SmsService {

    /**
     * How long a raw message is kept before it is eligible for purging.
     *
     * <p>Matches the mail path. The parsed transaction is the durable record;
     * the original text is retained only long enough to re-read it if a parser
     * rule turns out to be wrong.
     */
    private static final long RETAIN_DAYS = 400;

    private final JdbcTemplate jdbc;
    private final ParseService parsing;

    public SmsService(JdbcTemplate jdbc, ParseService parsing) {
        this.jdbc = jdbc;
        this.parsing = parsing;
    }

    /**
     * Stores a batch and, unless asked not to, reads it immediately.
     *
     * <p>Runs in one transaction so a batch either lands or does not. A partial
     * batch is not dangerous — the constraints make a retry safe — but it would
     * leave the counts in the response describing something other than what the
     * database holds, and those counts are shown to the user.
     */
    @Transactional
    public SmsIngestResult ingest(UUID userId, SmsBatchRequest request, boolean parse) {
        List<SmsMessageRequest> messages = request.safeMessages();
        if (messages.size() > SmsBatchRequest.MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "Send at most " + SmsBatchRequest.MAX_MESSAGES + " messages per request.");
        }

        UUID connectionId = deviceConnection(userId, request);
        SmsIngestResult.Builder result = new SmsIngestResult.Builder(connectionId);

        for (SmsMessageRequest message : messages) {
            result.counted();

            // The app has already applied these rules. It is asked again here
            // because a client's word is not evidence: see SmsFilter.
            SmsFilter.Decision decision = SmsFilter.check(message.sender(), message.body());
            if (!decision.accepted()) {
                result.skipped(decision.reason());
                continue;
            }

            if (store(userId, connectionId, message)) {
                result.storedOne();
            } else {
                result.duplicate();
            }
        }

        markSynced(connectionId);

        // Parsing is skipped when nothing new landed, which is the common case
        // on a rescan: there is no work to do and no reason to make the phone
        // wait for a query that will find an empty queue.
        ParseResult parsed = parse && result.stored() > 0 ? parsing.parseAll(userId) : null;
        return result.build(parsed);
    }

    /**
     * Finds or creates the {@code source_connection} standing for this handset.
     *
     * <p>A device gets a row of its own so it appears in the connections list
     * beside Gmail and Outlook, and can be revoked the same way. Without it the
     * user could grant SMS access on a phone they later lose and have no way to
     * cut it off.
     *
     * <p>The upsert keys on {@code (user_id, provider, external_account)},
     * which already carries a unique constraint. Reinstalling the app produces
     * a new device id and therefore a new row; that is the correct outcome,
     * since the reinstalled app cannot see the old one's queue state.
     */
    private UUID deviceConnection(UUID userId, SmsBatchRequest request) {
        String deviceId = request.deviceId() == null || request.deviceId().isBlank()
                ? "default"
                : request.deviceId().trim();
        String label = request.deviceName() == null || request.deviceName().isBlank()
                ? "Android phone"
                : request.deviceName().trim();

        return jdbc.queryForObject("""
                insert into source_connections (user_id, provider, external_account, display_name, status)
                values (?, 'android_sms', ?, ?, 'active')
                on conflict (user_id, provider, external_account)
                    do update set display_name = excluded.display_name, updated_at = now()
                returning id
                """, UUID.class, userId, deviceId, label);
    }

    /**
     * Writes one message, or does nothing if it is already there.
     *
     * <p>The uniqueness that makes this safe lives in the schema, not here.
     * {@code on conflict do nothing} plus {@code (user_id, body_hash)} means a
     * batch can be replayed after a timeout, a dropped connection, or an
     * over-eager retry, and the second attempt simply reports duplicates. The
     * app never has to reason about what the server already received, which is
     * what lets its offline queue be as simple as it is.
     */
    private boolean store(UUID userId, UUID connectionId, SmsMessageRequest message) {
        Instant receivedAt = message.receivedAt();

        int inserted = jdbc.update("""
                insert into raw_messages
                    (user_id, connection_id, provider_message_id, body_hash,
                     sender, subject, snippet, body, received_at, status, purge_after)
                values (?, ?, null, ?, ?, null, ?, ?, ?, 'pending', ?)
                on conflict do nothing
                """,
                userId,
                connectionId,
                SmsFingerprint.of(message.sender(), receivedAt, message.body()),
                message.sender(),
                snippet(message.body()),
                message.body(),
                receivedAt == null ? null : Timestamp.from(receivedAt),
                Timestamp.from(Instant.now().plusSeconds(RETAIN_DAYS * 86400L)));

        return inserted > 0;
    }

    /** First line's worth, for lists that show a message without opening it. */
    private static String snippet(String body) {
        String flattened = body.replaceAll("\\s+", " ").trim();
        return flattened.length() <= 140 ? flattened : flattened.substring(0, 139) + "…";
    }

    /**
     * Records that this device checked in.
     *
     * <p>Shown on the connections screen as "last synced". It is the only
     * signal that a phone has silently stopped uploading — a permission
     * revoked in Android settings, or battery optimisation killing the
     * receiver — neither of which produces an error anywhere else.
     */
    private void markSynced(UUID connectionId) {
        jdbc.update(
                "update source_connections set last_synced_at = now(), last_error = null where id = ?",
                connectionId);
    }
}
