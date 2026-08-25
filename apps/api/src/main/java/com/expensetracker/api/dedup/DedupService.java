package com.expensetracker.api.dedup;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies {@link DedupEngine} to real rows: finds candidates, merges the
 * certain ones, and queues the rest for review.
 *
 * <p>Duplicates are never deleted. The loser keeps its row and gains a
 * {@code merged_into_id}, so a wrong merge is always reversible and the
 * original evidence survives.
 */
@Service
public class DedupService {

    /**
     * Candidates are narrowed by the {@code transactions_dedup_idx} columns
     * (user, exact amount, time window) before any scoring runs, so the engine
     * only ever sees a handful of rows.
     *
     * <p>Transfer legs are excluded. A transfer is two rows bound by
     * {@code transfer_id}, and merging one leg away would leave the other
     * dangling — a transfer with a single side is corrupt data.
     */
    private static final String CANDIDATE_SQL = """
            select t.id, t.amount, t.currency, t.direction::text as direction, t.occurred_at,
                   t.account_id, t.merchant_id, m.normalized_name,
                   t.external_ref, t.created_at, t.import_batch_id,
                   coalesce(sc.provider::text,
                            case when t.import_batch_id is not null then 'csv_import' else 'manual' end
                   ) as source_provider
            from transactions t
            left join merchants m on m.id = t.merchant_id
            left join source_connections sc on sc.id = t.source_id
            where t.user_id = ?
              and t.id <> ?
              and t.amount = ?
              and t.currency = ?
              and t.direction = ?::transaction_direction
              and t.occurred_at between ? and ?
              and t.deleted_at is null
              and t.merged_into_id is null
              and t.transfer_id is null
            order by t.occurred_at
            limit 25
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DedupService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Screens a newly written transaction against existing ones.
     *
     * <p>Runs after the insert rather than before, so the row is already
     * durable: a crash mid-screen leaves an un-screened transaction, which the
     * user can still see and fix, rather than losing it entirely.
     */
    @Transactional
    public Outcome screen(UUID userId, UUID transactionId) {
        Optional<Row> subject = load(userId, transactionId);
        if (subject.isEmpty()) {
            return Outcome.none();
        }

        Row row = subject.get();
        List<Row> candidates = findCandidates(userId, row);

        Row bestReviewMatch = null;
        DedupVerdict bestReviewVerdict = null;

        for (Row candidate : candidates) {
            DedupVerdict verdict = DedupEngine.compare(row.toCandidate(), candidate.toCandidate());
            DedupVerdict.Decision decision =
                    MergePolicy.decide(verdict, row.provenance(), candidate.provenance());

            if (decision == DedupVerdict.Decision.AUTO_MERGE) {
                // Survivor is the older row: anything already pointing at a
                // transaction should keep pointing at the same one.
                Row survivor = candidate.createdAt.isBefore(row.createdAt) ? candidate : row;
                Row loser = survivor == candidate ? row : candidate;
                merge(userId, survivor.id, loser.id, verdict);
                return Outcome.merged(survivor.id, loser.id, verdict);
            }

            if (decision == DedupVerdict.Decision.REVIEW
                    && (bestReviewVerdict == null || verdict.score() > bestReviewVerdict.score())) {
                bestReviewMatch = candidate;
                bestReviewVerdict = verdict;
            }
        }

        if (bestReviewMatch != null) {
            recordCandidate(userId, row.id, bestReviewMatch.id, bestReviewVerdict);
            return Outcome.review(row.id, bestReviewMatch.id, bestReviewVerdict);
        }

        return Outcome.none();
    }

    /**
     * Finds an existing transaction carrying the same bank reference.
     *
     * <p>A bank reference is proof rather than inference: the same RRN or UTR
     * is the same payment. The database enforces that with a unique index, so
     * a second report of one has to be folded in <em>before</em> the insert is
     * attempted — otherwise re-importing an overlapping statement fails
     * outright instead of deduplicating, which is the single most common thing
     * a user does.
     *
     * <p>Returns the survivor when the matched row has itself been merged away,
     * so enrichment always lands on the row the user can actually see.
     */
    public Optional<UUID> findByReference(UUID userId, String externalRef) {
        if (externalRef == null || externalRef.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(jdbc.query("""
                select coalesce(t.merged_into_id, t.id)
                from transactions t
                where t.user_id = ? and t.external_ref = ? and t.deleted_at is null
                limit 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, userId, externalRef));
    }

    /**
     * Folds a report that was never inserted into the transaction it duplicates.
     *
     * <p>Only gaps are filled, never overwritten: a bank SMS knows the account
     * while an email knows the merchant, and the surviving row should carry
     * whichever facts either source happened to have.
     */
    @Transactional
    public Outcome absorb(UUID userId, UUID survivorId, UUID accountId, UUID categoryId,
            UUID merchantId, String description) {
        jdbc.update("""
                update transactions
                   set account_id  = coalesce(account_id, ?),
                       category_id = coalesce(category_id, ?),
                       merchant_id = coalesce(merchant_id, ?),
                       description = coalesce(description, ?)
                 where id = ? and user_id = ?
                """, accountId, categoryId, merchantId, description, survivorId, userId);

        return Outcome.absorbed(survivorId);
    }

    /**
     * Scores a not-yet-saved transaction against what is already stored,
     * writing nothing.     *
     * <p>Used by the import preview so the user sees which rows would be
     * treated as duplicates *before* committing an import, rather than
     * discovering it afterwards.
     */
    public Optional<Assessment> assess(UUID userId, DedupCandidate probe, Provenance provenance) {
        List<Row> candidates = findCandidates(userId, new UUID(0, 0), probe.amount(),
                probe.currency(), probe.direction(), probe.occurredAt());

        Assessment best = null;

        for (Row candidate : candidates) {
            DedupVerdict verdict = DedupEngine.compare(probe, candidate.toCandidate());
            DedupVerdict.Decision decision =
                    MergePolicy.decide(verdict, provenance, candidate.provenance());

            if (decision == DedupVerdict.Decision.DISTINCT) {
                continue;
            }
            if (best == null || verdict.score() > best.score()) {
                best = new Assessment(
                        decision == DedupVerdict.Decision.AUTO_MERGE ? "merge" : "review",
                        verdict.score(),
                        candidate.id);
            }
        }

        return Optional.ofNullable(best);
    }

    /**
     * Folds {@code loserId} into {@code survivorId}.
     *
     * <p>The survivor is enriched from the duplicate first: an SMS alert knows
     * the account while an email knows the merchant, so the merged row should
     * carry whichever facts either source had.
     */
    @Transactional
    public void merge(UUID userId, UUID survivorId, UUID loserId, DedupVerdict verdict) {
        UUID groupId = ensureGroup(userId, survivorId);

        jdbc.update("""
                update transactions survivor
                   set account_id  = coalesce(survivor.account_id, loser.account_id),
                       category_id = coalesce(survivor.category_id, loser.category_id),
                       merchant_id = coalesce(survivor.merchant_id, loser.merchant_id),
                       external_ref = coalesce(survivor.external_ref, loser.external_ref),
                       description = coalesce(survivor.description, loser.description),
                       transaction_group_id = ?
                  from transactions loser
                 where survivor.id = ? and loser.id = ?
                   and survivor.user_id = ? and loser.user_id = ?
                """, groupId, survivorId, loserId, userId, userId);

        jdbc.update("""
                update transactions
                   set merged_into_id = ?, transaction_group_id = ?
                 where id = ? and user_id = ?
                """, survivorId, groupId, loserId, userId);

        // Recorded as already-merged so the pair is never re-offered for review.
        upsertCandidate(userId, survivorId, loserId, verdict, "merged", true);
    }

    /** Groups let the UI show "these three reports are one payment". */
    private UUID ensureGroup(UUID userId, UUID survivorId) {
        UUID existing = jdbc.query("""
                select transaction_group_id from transactions
                where id = ? and user_id = ? and transaction_group_id is not null
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, survivorId, userId);

        if (existing != null) {
            return existing;
        }

        return jdbc.queryForObject("""
                insert into transaction_groups (user_id, primary_transaction_id)
                values (?, ?) returning id
                """, UUID.class, userId, survivorId);
    }

    private void recordCandidate(UUID userId, UUID a, UUID b, DedupVerdict verdict) {
        upsertCandidate(userId, a, b, verdict, "pending", false);
    }

    /**
     * The unique constraint is on the ordered pair, so ids are sorted before
     * writing; otherwise (A,B) and (B,A) would both be insertable and the same
     * pair could be queued twice.
     */
    private void upsertCandidate(UUID userId, UUID first, UUID second,
            DedupVerdict verdict, String status, boolean resolved) {
        UUID a = first.compareTo(second) <= 0 ? first : second;
        UUID b = first.compareTo(second) <= 0 ? second : first;

        jdbc.update("""
                insert into duplicate_candidates (
                    user_id, transaction_a, transaction_b, score, signals, status, resolved_at)
                values (?, ?, ?, ?, ?::jsonb, ?::duplicate_status, ?)
                on conflict (transaction_a, transaction_b) do update
                   set score = excluded.score,
                       signals = excluded.signals,
                       status = excluded.status,
                       resolved_at = excluded.resolved_at
                """,
                userId, a, b, verdict.score(), toJson(verdict.signals()), status,
                resolved ? java.sql.Timestamp.from(Instant.now()) : null);
    }

    // ---- review queue ------------------------------------------------------

    public List<PendingPair> pending(UUID userId, int limit) {
        return jdbc.query("""
                select dc.id, dc.score, dc.signals::text as signals, dc.created_at,
                       a.id as a_id, a.amount as a_amount, a.currency as a_currency,
                       a.occurred_at as a_occurred_at, a.description as a_description,
                       ma.name as a_merchant, aa.name as a_account,
                       b.id as b_id, b.amount as b_amount, b.currency as b_currency,
                       b.occurred_at as b_occurred_at, b.description as b_description,
                       mb.name as b_merchant, ab.name as b_account
                from duplicate_candidates dc
                join transactions a on a.id = dc.transaction_a
                join transactions b on b.id = dc.transaction_b
                left join merchants ma on ma.id = a.merchant_id
                left join merchants mb on mb.id = b.merchant_id
                left join accounts aa on aa.id = a.account_id
                left join accounts ab on ab.id = b.account_id
                where dc.user_id = ?
                  and dc.status = 'pending'
                  and a.deleted_at is null and b.deleted_at is null
                order by dc.score desc, dc.created_at
                limit ?
                """, (rs, i) -> new PendingPair(
                        rs.getObject("id", UUID.class),
                        rs.getDouble("score"),
                        rs.getString("signals"),
                        side(rs, "a"),
                        side(rs, "b")),
                userId, Math.min(Math.max(limit, 1), 100));
    }

    /** Confirms a queued pair is one payment. */
    @Transactional
    public void resolveMerge(UUID userId, UUID candidateId) {
        Pair pair = requirePending(userId, candidateId);

        // Keep the older row for the same reason auto-merge does.
        UUID survivor = pair.createdA.isBefore(pair.createdB) ? pair.a : pair.b;
        UUID loser = survivor.equals(pair.a) ? pair.b : pair.a;

        merge(userId, survivor, loser,
                DedupVerdict.of(pair.score, DedupVerdict.Decision.AUTO_MERGE,
                        Map.of("resolvedBy", "user")));
    }

    /** Marks a queued pair as two genuinely separate payments. */
    @Transactional
    public void resolve(UUID userId, UUID candidateId, String status) {
        if (!List.of("kept_both", "dismissed").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Status must be kept_both or dismissed.");
        }
        requirePending(userId, candidateId);

        jdbc.update("""
                update duplicate_candidates
                   set status = ?::duplicate_status, resolved_at = now()
                 where id = ? and user_id = ?
                """, status, candidateId, userId);
    }

    public long pendingCount(UUID userId) {
        Long count = jdbc.queryForObject("""
                select count(*) from duplicate_candidates
                where user_id = ? and status = 'pending'
                """, Long.class, userId);
        return count == null ? 0 : count;
    }

    private Pair requirePending(UUID userId, UUID candidateId) {
        List<Pair> found = jdbc.query("""
                select dc.transaction_a, dc.transaction_b, dc.score,
                       a.created_at as a_created, b.created_at as b_created
                from duplicate_candidates dc
                join transactions a on a.id = dc.transaction_a
                join transactions b on b.id = dc.transaction_b
                where dc.id = ? and dc.user_id = ? and dc.status = 'pending'
                """, (rs, i) -> new Pair(
                        rs.getObject("transaction_a", UUID.class),
                        rs.getObject("transaction_b", UUID.class),
                        rs.getDouble("score"),
                        rs.getTimestamp("a_created").toInstant(),
                        rs.getTimestamp("b_created").toInstant()),
                candidateId, userId);

        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No pending duplicate with that id.");
        }
        return found.get(0);
    }

    // ---- loading -----------------------------------------------------------

    /**
     * Loads the row being screened. Returns empty for a transfer leg, which
     * deliberately opts out of deduplication (see {@link #CANDIDATE_SQL}).
     */
    private Optional<Row> load(UUID userId, UUID transactionId) {
        return jdbc.query("""
                select t.id, t.amount, t.currency, t.direction::text as direction, t.occurred_at,
                       t.account_id, t.merchant_id, m.normalized_name,
                       t.external_ref, t.created_at, t.import_batch_id,
                       coalesce(sc.provider::text,
                                case when t.import_batch_id is not null then 'csv_import' else 'manual' end
                       ) as source_provider
                from transactions t
                left join merchants m on m.id = t.merchant_id
                left join source_connections sc on sc.id = t.source_id
                where t.user_id = ? and t.id = ?
                  and t.deleted_at is null
                  and t.merged_into_id is null
                  and t.transfer_id is null
                """, DedupService::mapRow, userId, transactionId).stream().findFirst();
    }

    private List<Row> findCandidates(UUID userId, Row row) {
        return findCandidates(userId, row.id, row.amount, row.currency, row.direction, row.occurredAt);
    }

    private List<Row> findCandidates(UUID userId, UUID excludeId, java.math.BigDecimal amount,
            String currency, String direction, Instant occurredAt) {
        Instant from = occurredAt.minus(DedupEngine.MAX_WINDOW);
        Instant to = occurredAt.plus(DedupEngine.MAX_WINDOW);

        return jdbc.query(CANDIDATE_SQL, DedupService::mapRow,
                userId, excludeId, amount, currency, direction,
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(to));
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("direction"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("account_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("normalized_name"),
                rs.getString("external_ref"),
                rs.getString("source_provider"),
                rs.getObject("import_batch_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    private static Summary side(ResultSet rs, String prefix) throws SQLException {
        return new Summary(
                rs.getObject(prefix + "_id", UUID.class),
                rs.getBigDecimal(prefix + "_amount"),
                rs.getString(prefix + "_currency"),
                rs.getTimestamp(prefix + "_occurred_at").toInstant(),
                rs.getString(prefix + "_description"),
                rs.getString(prefix + "_merchant"),
                rs.getString(prefix + "_account"));
    }

    private String toJson(Map<String, Object> signals) {
        try {
            return json.writeValueAsString(signals);
        } catch (JacksonException ex) {
            // Signals are explanatory only; losing them must not fail a merge.
            //
            // Jackson 3 made these unchecked, so the compiler no longer insists
            // on this catch. It stays because the reasoning is unchanged: a
            // merge is the valuable thing here, and an unserialisable signal
            // map should not be allowed to abort one.
            return "{}";
        }
    }

    // ---- types -------------------------------------------------------------

    private record Row(
            UUID id,
            java.math.BigDecimal amount,
            String currency,
            String direction,
            Instant occurredAt,
            UUID accountId,
            UUID merchantId,
            String normalizedMerchant,
            String externalRef,
            String sourceProvider,
            UUID importBatchId,
            Instant createdAt) {

        DedupCandidate toCandidate() {
            return new DedupCandidate(id, amount, currency, direction, occurredAt,
                    accountId, merchantId, normalizedMerchant, externalRef, sourceProvider);
        }

        Provenance provenance() {
            return new Provenance(sourceProvider, importBatchId);
        }
    }

    private record Pair(UUID a, UUID b, double score, Instant createdA, Instant createdB) {
    }

    public record Summary(
            UUID id,
            java.math.BigDecimal amount,
            String currency,
            Instant occurredAt,
            String description,
            String merchantName,
            String accountName) {
    }

    public record PendingPair(
            UUID id,
            double score,
            String signals,
            Summary a,
            Summary b) {
    }

    /** What screening *would* decide, for a row that has not been saved yet. */
    public record Assessment(String action, double score, UUID matchesTransactionId) {
    }

    /** What screening did, so the caller can tell the user. */
    public record Outcome(
            String action,
            UUID survivorId,
            UUID duplicateId,
            Double score,
            Map<String, Object> signals) {

        public static Outcome none() {
            return new Outcome("none", null, null, null, Map.of());
        }

        public static Outcome merged(UUID survivor, UUID duplicate, DedupVerdict verdict) {
            return new Outcome("merged", survivor, duplicate, verdict.score(), verdict.signals());
        }

        /** A duplicate caught by its bank reference, before anything was written. */
        public static Outcome absorbed(UUID survivor) {
            return new Outcome("merged", survivor, null, 1.0,
                    Map.of("externalRef", "equal", "mergedBy", "reference"));
        }

        public static Outcome review(UUID subject, UUID other, DedupVerdict verdict) {
            return new Outcome("review", subject, other, verdict.score(), verdict.signals());
        }

        public boolean isMerged() {
            return "merged".equals(action);
        }
    }
}
