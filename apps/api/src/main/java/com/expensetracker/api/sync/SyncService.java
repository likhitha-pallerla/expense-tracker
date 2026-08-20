package com.expensetracker.api.sync;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.expensetracker.api.connections.MailProvider;

/**
 * Brings new mail in.
 *
 * <h2>Why nothing here is scheduled</h2>
 *
 * <p>The obvious design is a job that polls every mailbox every few minutes.
 * That design does not survive contact with free hosting: the API sleeps when
 * nobody is using it, so a scheduled task is a task that mostly does not run,
 * and one that half-runs as the instance is shutting down. Anything that must
 * happen reliably has to happen while a request is in flight.
 *
 * <p>So syncing is something a user asks for, and the app asks on their behalf
 * when they open the page. It is honest about it: every run is written down,
 * including the ones that failed, so "why is nothing importing" has an answer.
 *
 * <h2>Why a run has a budget</h2>
 *
 * <p>A first sync of a mailbox with ten years of mail is thousands of requests.
 * On a platform that kills long requests, attempting it produces nothing at all
 * — no messages stored, no cursor advanced, the same work to do again next
 * time. Stopping after a fixed number of messages and reporting {@code hasMore}
 * turns one impossible request into a handful of ordinary ones, each of which
 * makes permanent progress.
 *
 * <h2>What is stored, and what is not</h2>
 *
 * <p>Only mail that {@link MailQuery} recognises as a payment alert is written
 * down. Everything else is held in memory for the length of one request and
 * discarded. That is not a performance decision; it is the promise the
 * connections page makes to the user, and this is the only place it can be
 * kept.
 */
@Service
public class SyncService {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    /**
     * Messages per run.
     *
     * <p>Sized for the slowest realistic case: Gmail needs one request per
     * message, and 200 of those on a cold free instance is comfortably inside a
     * request timeout while still clearing a normal month of alerts in one go.
     */
    private static final int BUDGET = 200;

    /** How long a stored message is kept before it can be purged. */
    private static final int RETAIN_DAYS = 400;

    private final JdbcTemplate jdbc;
    private final AccessTokens tokens;
    private final Map<MailProvider, MailFetcher> fetchers = new EnumMap<>(MailProvider.class);

    public SyncService(JdbcTemplate jdbc, AccessTokens tokens, List<MailFetcher> available) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        for (MailFetcher fetcher : available) {
            fetchers.put(fetcher.provider(), fetcher);
        }
    }

    // ---- entry points -------------------------------------------------------

    /** Syncs every mailbox the user has linked that is in a fit state to try. */
    public List<SyncRunView> syncAll(UUID userId) {
        List<Connection> connections = jdbc.query("""
                select id, provider::text as provider, status::text as status,
                       encrypted_access_token, token_expires_at, encrypted_refresh_token,
                       sync_cursor, backfill_from
                from source_connections
                where user_id = ? and provider in ('gmail', 'outlook')
                  and status in ('active', 'error')
                order by created_at
                """, (rs, row) -> new Connection(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("encrypted_access_token"),
                        instant(rs.getTimestamp("token_expires_at")),
                        rs.getString("encrypted_refresh_token"),
                        rs.getString("sync_cursor"),
                        instant(rs.getTimestamp("backfill_from"))),
                userId);

        List<SyncRunView> runs = new ArrayList<>();
        for (Connection connection : connections) {
            runs.add(run(userId, connection));
        }
        return runs;
    }

    /** Syncs one mailbox. */
    public SyncRunView sync(UUID userId, UUID connectionId) {
        List<Connection> found = jdbc.query("""
                select id, provider::text as provider, status::text as status,
                       encrypted_access_token, token_expires_at, encrypted_refresh_token,
                       sync_cursor, backfill_from
                from source_connections
                where user_id = ? and id = ? and provider in ('gmail', 'outlook')
                """, (rs, row) -> new Connection(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("encrypted_access_token"),
                        instant(rs.getTimestamp("token_expires_at")),
                        rs.getString("encrypted_refresh_token"),
                        rs.getString("sync_cursor"),
                        instant(rs.getTimestamp("backfill_from"))),
                userId, connectionId);

        if (found.isEmpty()) {
            throw new IllegalArgumentException("No such mailbox.");
        }
        return run(userId, found.get(0));
    }

    /** Recent runs, for the UI to explain itself with. */
    public List<SyncRunView> history(UUID userId, int limit) {
        return jdbc.query("""
                select r.id, r.connection_id, c.provider::text as provider,
                       r.started_at, r.finished_at, r.status,
                       r.fetched_count, r.stored_count, r.skipped_count,
                       r.has_more, r.error
                from sync_runs r
                join source_connections c on c.id = r.connection_id
                where r.user_id = ?
                order by r.started_at desc
                limit ?
                """, (rs, row) -> new SyncRunView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("connection_id", UUID.class),
                        rs.getString("provider"),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("finished_at")),
                        rs.getString("status"),
                        rs.getInt("fetched_count"),
                        rs.getInt("stored_count"),
                        rs.getInt("skipped_count"),
                        rs.getBoolean("has_more"),
                        rs.getString("error")),
                userId, Math.min(Math.max(limit, 1), 50));
    }

    // ---- one run ------------------------------------------------------------

    private SyncRunView run(UUID userId, Connection connection) {
        MailProvider provider = MailProvider.from(connection.provider())
                .orElseThrow(() -> new IllegalStateException("Unknown provider stored: " + connection.provider()));

        MailFetcher fetcher = fetchers.get(provider);
        UUID runId = startRun(userId, connection.id());

        if (fetcher == null) {
            // Configuration removed while a mailbox was still linked.
            return failRun(runId, userId, connection.id(),
                    "This mail provider is not available on this server right now.");
        }

        try {
            String accessToken = tokens.forConnection(userId, connection.id(), provider,
                    connection.encryptedAccessToken(), connection.tokenExpiresAt(),
                    connection.encryptedRefreshToken());

            FetchResult result = fetcher.fetch(accessToken, connection.syncCursor(),
                    connection.backfillFrom(), BUDGET);

            int stored = 0;
            int skipped = 0;
            for (MailMessage message : result.messages()) {
                if (!MailQuery.looksRelevant(message)) {
                    // Not skipped: never ours. Counting it would make the
                    // duplicate figure meaningless.
                    continue;
                }
                if (store(userId, connection.id(), message)) {
                    stored++;
                } else {
                    skipped++;
                }
            }

            advance(connection.id(), result, userId);
            return finishRun(runId, userId, connection.id(),
                    result.messages().size(), stored, skipped, result.hasMore());

        } catch (MailFetchException e) {
            log.warn("Sync failed for connection {}: {}", connection.id(), e.getMessage());
            return failRun(runId, userId, connection.id(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Sync failed unexpectedly for connection {}", connection.id(), e);
            return failRun(runId, userId, connection.id(),
                    "Something went wrong while checking this mailbox.");
        }
    }

    /**
     * Writes one message, or finds we already had it.
     *
     * <p>{@code on conflict do nothing} with no target on purpose: {@code
     * raw_messages} has two different unique constraints — the provider's own
     * id, and the content hash — and either one firing means the same thing.
     * Naming one would let the other throw.
     *
     * @return true if the row was new
     */
    private boolean store(UUID userId, UUID connectionId, MailMessage message) {
        int inserted = jdbc.update("""
                insert into raw_messages
                    (user_id, connection_id, provider_message_id, body_hash,
                     sender, subject, snippet, body, received_at, status, purge_after)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
                on conflict do nothing
                """,
                userId,
                connectionId,
                message.providerMessageId(),
                BodyHash.of(message.subject(), message.body()),
                message.sender(),
                message.subject(),
                message.snippet(),
                message.body(),
                message.receivedAt() == null ? null : Timestamp.from(message.receivedAt()),
                Timestamp.from(Instant.now().plusSeconds(RETAIN_DAYS * 86400L)));

        return inserted > 0;
    }

    /**
     * Moves the mailbox forward.
     *
     * <p>The cursor is written even when the run stored nothing. A run that
     * found only irrelevant mail has still made progress, and leaving the
     * cursor behind would make the next run read the same mail again, forever.
     */
    private void advance(UUID connectionId, FetchResult result, UUID userId) {
        jdbc.update("""
                update source_connections
                set sync_cursor = ?,
                    last_synced_at = now(),
                    backfilled_at = case when ? then backfilled_at else coalesce(backfilled_at, now()) end,
                    last_error = null,
                    status = case when status = 'error' then 'active'::connection_status else status end
                where id = ? and user_id = ?
                """, result.nextCursor(), result.hasMore(), connectionId, userId);
    }

    // ---- run bookkeeping ----------------------------------------------------

    private UUID startRun(UUID userId, UUID connectionId) {
        return jdbc.queryForObject("""
                insert into sync_runs (user_id, connection_id, status)
                values (?, ?, 'running')
                returning id
                """, UUID.class, userId, connectionId);
    }

    private SyncRunView finishRun(UUID runId, UUID userId, UUID connectionId,
                                  int fetched, int stored, int skipped, boolean more) {
        jdbc.update("""
                update sync_runs
                set status = 'ok', finished_at = now(),
                    fetched_count = ?, stored_count = ?, skipped_count = ?, has_more = ?
                where id = ?
                """, fetched, stored, skipped, more, runId);

        jdbc.update("update source_connections set last_sync_run_id = ? where id = ?",
                runId, connectionId);

        return one(userId, runId);
    }

    private SyncRunView failRun(UUID runId, UUID userId, UUID connectionId, String error) {
        jdbc.update("""
                update sync_runs
                set status = 'failed', finished_at = now(), error = ?
                where id = ?
                """, error, runId);

        // The connection's own error is set too. sync_runs is the diary;
        // source_connections is what the connections page reads, and a mailbox
        // that is failing should look wrong there without anyone going digging.
        jdbc.update("""
                update source_connections
                set last_error = ?,
                    last_sync_run_id = ?,
                    status = case when status = 'active' then 'error'::connection_status else status end
                where id = ?
                """, error, runId, connectionId);

        return one(userId, runId);
    }

    private SyncRunView one(UUID userId, UUID runId) {
        return jdbc.queryForObject("""
                select r.id, r.connection_id, c.provider::text as provider,
                       r.started_at, r.finished_at, r.status,
                       r.fetched_count, r.stored_count, r.skipped_count,
                       r.has_more, r.error
                from sync_runs r
                join source_connections c on c.id = r.connection_id
                where r.id = ? and r.user_id = ?
                """, (rs, row) -> new SyncRunView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("connection_id", UUID.class),
                        rs.getString("provider"),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("finished_at")),
                        rs.getString("status"),
                        rs.getInt("fetched_count"),
                        rs.getInt("stored_count"),
                        rs.getInt("skipped_count"),
                        rs.getBoolean("has_more"),
                        rs.getString("error")),
                runId, userId);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Connection(
            UUID id,
            String provider,
            String encryptedAccessToken,
            Instant tokenExpiresAt,
            String encryptedRefreshToken,
            String syncCursor,
            Instant backfillFrom) {
    }
}
