package com.expensetracker.api.parsing;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.expensetracker.api.profile.UserSettings;
import com.expensetracker.api.transactions.Origin;
import com.expensetracker.api.transactions.TransactionRequest;
import com.expensetracker.api.transactions.TransactionService;

/**
 * Turning stored alerts into transactions.
 *
 * <p>This runs after sync and is separate from it on purpose. Fetching mail
 * costs provider quota and can fail for reasons that have nothing to do with
 * the content; reading it costs nothing and can be repeated whenever a rule
 * improves. Keeping them apart means a better rule can be applied to mail
 * already in hand without going back to Gmail for it.
 *
 * <p>Every message ends in one of three states. {@code parsed} produced a
 * transaction. {@code failed} did not, and says why. {@code ignored} means a
 * rule matched but the message was not about a payment at all — a balance
 * summary, an OTP — and it should not be tried again.
 */
@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    /**
     * How many messages one call will read. Large enough to clear a normal
     * backlog in one press, small enough that the request returns before any
     * proxy in front of the free instance decides it has hung.
     */
    private static final int DEFAULT_LIMIT = 300;

    private final JdbcTemplate jdbc;
    private final ParserRules rules;
    private final TransactionService transactions;
    private final UserSettings settings;
    private final AiAlertParser aiParser;
    private final TransactionTemplate tx;

    public ParseService(JdbcTemplate jdbc, ParserRules rules, TransactionService transactions,
            UserSettings settings, AiAlertParser aiParser,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.transactions = transactions;
        this.settings = settings;
        this.aiParser = aiParser;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** One stored message, as much as parsing needs of it. */
    private record Pending(UUID id, UUID connectionId, String sender, String subject,
            String body, Instant receivedAt, String provider) {

        /**
         * Whether this arrived as mail, and so has a sender worth judging.
         *
         * <p>SMS is excluded because it has no comparable sender: a text
         * arrives from a shortcode, not a domain, and {@code SmsFilter} has
         * always refused those it cannot attribute. CSV imports and manual
         * entries are the user's own doing and are not gated at all.
         */
        boolean isMail() {
            return "gmail".equals(provider) || "outlook".equals(provider);
        }
    }

    /**
     * Reads every message waiting for this user.
     *
     * <p>Not transactional as a whole, deliberately: one message that cannot be
     * read must not roll back the fifty that could. Each message is its own
     * unit of work.
     */
    public ParseResult parseAll(UUID userId) {
        List<ParserRule> loaded = rules.forUser(userId);
        if (loaded.isEmpty()) {
            // Possible only if every rule in the database is broken. Refusing
            // here leaves the messages pending; marking them failed would throw
            // away work that a fixed rule would have completed.
            log.error("No usable parser rules; leaving messages pending for user {}", userId);
            return new ParseResult(0, 0, 0, 0, 0, 0);
        }

        ZoneId zone = settings.zoneOf(userId);
        List<Pending> pending = pending(userId);
        Set<String> trusted = trustedDomains(userId);

        int imported = 0;
        int merged = 0;
        int failed = 0;
        int ignored = 0;
        int quarantined = 0;

        for (Pending message : pending) {
            Outcome outcome = parseOne(userId, message, loaded, zone, trusted);
            switch (outcome) {
                case IMPORTED -> imported++;
                case MERGED -> merged++;
                case IGNORED -> ignored++;
                case FAILED -> failed++;
                case QUARANTINED -> quarantined++;
            }
        }
        return new ParseResult(pending.size(), imported, merged, ignored, failed, quarantined);
    }

    private enum Outcome { IMPORTED, MERGED, IGNORED, FAILED, QUARANTINED }

    /**
     * One message, in its own database transaction so a failure is contained.
     *
     * <p>Uses {@link TransactionTemplate} rather than {@code @Transactional}
     * because this is called from within the same class. Spring's annotation
     * works through a proxy, and a call from one method of a bean to another
     * never passes through it — the annotation would be silently ignored and
     * every message would share one transaction, which is exactly what this is
     * trying to avoid.
     */
    private Outcome parseOne(UUID userId, Pending message, List<ParserRule> loaded, ZoneId zone,
            Set<String> trusted) {
        try {
            return tx.execute(status -> {
                // Before anything is read, let alone written. A message that
                // cannot be attributed to an institution must not become a
                // transaction on the strength of its own contents, because its
                // contents are exactly what an attacker controls.
                //
                // Mail only: a text message has no sender domain, and SmsFilter
                // has always refused shortcodes it cannot attribute.
                if (message.isMail()) {
                    SenderTrust.Verdict verdict = SenderTrust.judge(message.sender(), trusted);
                    if (!verdict.isAccepted()) {
                        quarantine(userId, message, verdict);
                        return Outcome.QUARANTINED;
                    }
                }

                ParsedAlert parsed = AlertParser.parse(message.sender(), message.subject(),
                        message.body(), message.receivedAt(), zone, loaded);

                double confidence = 0;
                String readBy = "rule";

                if (!parsed.isSuccess()) {
                    // Only now, and only for this message. The model is asked
                    // exactly when the deterministic reader has already said it
                    // cannot help, so it can add messages but never change the
                    // reading of one a rule understood.
                    Optional<AiAlertParser.Reading> guessed = aiParser.read(userId,
                            message.sender(), message.subject(), message.body(),
                            message.receivedAt(), zone);

                    if (guessed.isEmpty()) {
                        markFailed(userId, message.id(), parsed);
                        return Outcome.FAILED;
                    }
                    parsed = guessed.get().alert();
                    confidence = guessed.get().confidence();
                    readBy = "ai";
                }

                TransactionService.Created created = transactions.create(userId,
                        toRequest(userId, message, parsed),
                        Origin.message(message.connectionId(), message.id()));

                markParsed(userId, message.id(), parsed, readBy, confidence);
                return created.dedup().isMerged() ? Outcome.MERGED : Outcome.IMPORTED;
            });
        } catch (RuntimeException ex) {
            // The message ends up failed with a reason rather than pending
            // forever, so a retry is the user's decision rather than an
            // accident. The exception text is not shown: it is a stack-trace
            // detail, not something a person can act on.
            log.warn("Could not parse message {}: {}", message.id(), ex.toString());
            jdbc.update("""
                    update raw_messages
                       set status = 'failed', parse_error = ?, parsed_at = now()
                     where id = ? and user_id = ?
                    """,
                    "Something went wrong reading this message. Retrying may help.",
                    message.id(), userId);
            return Outcome.FAILED;
        }
    }

    private TransactionRequest toRequest(UUID userId, Pending message, ParsedAlert parsed) {
        UUID accountId = accountFor(userId, parsed.last4()).orElse(null);
        return new TransactionRequest(
                parsed.isCredit() ? "income" : "expense",
                parsed.direction(),
                parsed.amount(),
                currencyFor(userId, accountId),
                parsed.occurredAt(),
                describe(message, parsed),
                null,
                null,
                accountId,
                null,
                parsed.merchant(),
                parsed.reference(),
                null,
                null);
    }

    /**
     * What the user sees in their list.
     *
     * <p>The merchant is preferred; the subject line is the fallback because it
     * is what they would have seen in their inbox, which makes the transaction
     * recognisable even when the merchant could not be read.
     */
    private String describe(Pending message, ParsedAlert parsed) {
        if (parsed.merchant() != null) {
            return parsed.merchant();
        }
        String subject = message.subject();
        if (subject != null && !subject.isBlank()) {
            return subject.strip().length() > 200 ? subject.strip().substring(0, 200) : subject.strip();
        }
        return parsed.isCredit() ? "Money received" : "Payment";
    }

    /**
     * Finds the account four digits belong to.
     *
     * <p>Two accounts ending in the same four digits is rare but real, and
     * guessing between them would put money in the wrong place silently. In
     * that case the transaction is created with no account, which is visible
     * and fixable, rather than with the wrong one, which is neither.
     */
    private Optional<UUID> accountFor(UUID userId, String last4) {
        if (last4 == null) {
            return Optional.empty();
        }
        List<UUID> matches = jdbc.queryForList("""
                select id from accounts
                 where user_id = ? and last4 = ? and is_archived = false
                """, UUID.class, userId, last4);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private String currencyFor(UUID userId, UUID accountId) {
        if (accountId == null) {
            return null;
        }
        return jdbc.query("select currency from accounts where id = ? and user_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, accountId, userId);
    }

    private List<Pending> pending(UUID userId) {
        return jdbc.query("""
                select r.id, r.connection_id, r.sender, r.subject, r.body, r.received_at,
                       c.provider::text as provider
                  from raw_messages r
                  left join source_connections c on c.id = r.connection_id
                 where r.user_id = ? and r.status = 'pending'
                   and not exists (
                       select 1 from transactions t
                        where t.raw_message_id = r.id and t.deleted_at is null)
                 order by r.received_at nulls last
                 limit ?
                """,
                (rs, row) -> new Pending(
                        rs.getObject("id", UUID.class),
                        rs.getObject("connection_id", UUID.class),
                        rs.getString("sender"),
                        rs.getString("subject"),
                        rs.getString("body"),
                        rs.getTimestamp("received_at") == null
                                ? null
                                : rs.getTimestamp("received_at").toInstant(),
                        rs.getString("provider")),
                userId, DEFAULT_LIMIT);
    }

    /**
     * Domains this user has accepted as sources of payment alerts.
     *
     * <p>Read once per pass rather than per message: the list is small, changes
     * only when the user acts, and a query per message would turn one sync into
     * hundreds of round trips.
     */
    private Set<String> trustedDomains(UUID userId) {
        return Set.copyOf(jdbc.queryForList(
                "select domain from trusted_senders where user_id = ?",
                String.class, userId));
    }

    /**
     * Holds a message back and says why, in words the user is asked to act on.
     *
     * <p>Not deleted, and not marked failed. Failed means "we tried and could
     * not read it", which invites a retry that would fail the same way. This is
     * a different thing: the message was readable and was deliberately not
     * acted on, and only the user can resolve it.
     */
    private void quarantine(UUID userId, Pending message, SenderTrust.Verdict verdict) {
        String reason = switch (verdict) {
            case NOT_AN_INSTITUTION -> SenderTrust.domainOf(message.sender())
                    .map(domain -> "This came from " + domain
                            + ", which is a personal mail provider rather than a bank. "
                            + "Anyone can send from an address there, so it will not be "
                            + "recorded automatically.")
                    .orElse("This message has no sender we can check, so it will not be "
                            + "recorded automatically.");
            case UNRECOGNISED -> "This came from " + SenderTrust.domainOf(message.sender())
                    .orElse("an unknown address")
                    + ", which we do not recognise as a bank. Confirm the sender to record "
                    + "it and anything else from there.";
            default -> "Held for review.";
        };

        jdbc.update("""
                update raw_messages
                   set status = 'quarantined', quarantine_reason = ?, parsed_at = now()
                 where id = ? and user_id = ?
                """, reason, message.id(), userId);
    }

    private void markParsed(UUID userId, UUID messageId, ParsedAlert parsed,
            String readBy, double confidence) {
        jdbc.update("""
                update raw_messages
                   set status = 'parsed', parser_rule_id = ?, parse_error = null,
                       parsed_at = now(), parse_notes = ?,
                       parsed_by = ?, ai_confidence = ?
                 where id = ? and user_id = ?
                """,
                parsed.ruleId(),
                notesFor(parsed, readBy),
                readBy,
                "ai".equals(readBy) ? confidence : null,
                messageId, userId);
    }

    /**
     * What to tell the user about how this one was read.
     *
     * <p>An AI reading is always disclosed, even a confident one. Somebody
     * scanning their transactions has a right to know which of them were
     * guessed at, and the note is the only place that can be said in the
     * message list.
     */
    private static String notesFor(ParsedAlert parsed, String readBy) {
        String dateNote = parsed.dateExact() ? null
                : "Used the date this alert arrived; "
                        + "the message did not carry a date we could read.";
        if (!"ai".equals(readBy)) {
            return dateNote;
        }
        String aiNote = "No rule matched this message, so it was read by AI. "
                + "Worth a glance.";
        return dateNote == null ? aiNote : aiNote + " " + dateNote;
    }

    private void markFailed(UUID userId, UUID messageId, ParsedAlert parsed) {
        jdbc.update("""
                update raw_messages
                   set status = 'failed', parser_rule_id = ?, parse_error = ?, parsed_at = now()
                 where id = ? and user_id = ?
                """, parsed.ruleId(), parsed.problem(), messageId, userId);
    }

    /**
     * Puts failed messages back in the queue.
     *
     * <p>Exists because a failure is usually a missing rule rather than a bad
     * message. Without this, improving a rule would do nothing for the mail
     * already collected — the user would have to disconnect and re-import to
     * benefit, which is absurd.
     */
    @Transactional
    public int retryFailed(UUID userId) {
        return jdbc.update("""
                update raw_messages
                   set status = 'pending', parse_error = null, parse_notes = null
                 where user_id = ? and status = 'failed'
                """, userId);
    }

    /**
     * Stops trying to read a message.
     *
     * <p>Some mail simply is not a payment however hard you look at it. Marking
     * it ignored keeps it out of the failure list without deleting it, so the
     * decision stays reversible.
     */
    @Transactional
    public boolean ignore(UUID userId, UUID messageId) {
        return jdbc.update("""
                update raw_messages
                   set status = 'ignored', parse_error = null, parsed_at = now()
                 where id = ? and user_id = ? and status <> 'parsed'
                """, messageId, userId) > 0;
    }

    /** Messages that could not be read, newest first. */
    public List<UnreadMessage> unread(UUID userId, int limit) {
        return jdbc.query("""
                select r.id, r.subject, r.sender, r.received_at, r.parse_error, r.snippet,
                       pr.name as rule_name
                  from raw_messages r
                  left join parser_rules pr on pr.id = r.parser_rule_id
                 where r.user_id = ? and r.status = 'failed'
                 order by r.received_at desc nulls last
                 limit ?
                """,
                (rs, row) -> new UnreadMessage(
                        rs.getObject("id", UUID.class),
                        rs.getString("subject"),
                        rs.getString("sender"),
                        rs.getTimestamp("received_at") == null
                                ? null
                                : rs.getTimestamp("received_at").toInstant(),
                        rs.getString("rule_name"),
                        rs.getString("parse_error"),
                        rs.getString("snippet")),
                userId, Math.clamp(limit, 1, 200));
    }

    /** How many messages are waiting, so the button can say something useful. */
    public ParseQueue queue(UUID userId) {
        return jdbc.queryForObject("""
                select
                  count(*) filter (where status = 'pending')     as pending,
                  count(*) filter (where status = 'failed')      as failed,
                  count(*) filter (where status = 'parsed')      as parsed,
                  count(*) filter (where status = 'quarantined') as quarantined
                from raw_messages where user_id = ?
                """,
                (rs, row) -> new ParseQueue(rs.getInt("pending"), rs.getInt("failed"),
                        rs.getInt("parsed"), rs.getInt("quarantined")),
                userId);
    }

    /**
     * Senders being held, grouped by domain.
     *
     * <p>Grouped because the question is about the sender, not the message. Ten
     * alerts from one unrecognised bank is one decision, and asking it ten
     * times teaches the user to stop reading it.
     */
    public List<HeldSender> heldSenders(UUID userId) {
        return jdbc.query("""
                select r.sender, count(*) as messages, max(r.received_at) as latest,
                       min(r.quarantine_reason) as reason,
                       (array_agg(r.subject order by r.received_at desc))[1] as latest_subject
                  from raw_messages r
                 where r.user_id = ? and r.status = 'quarantined'
                 group by r.sender
                 order by max(r.received_at) desc nulls last
                 limit 50
                """,
                (rs, row) -> new HeldSender(
                        rs.getString("sender"),
                        SenderTrust.domainOf(rs.getString("sender")).orElse(null),
                        rs.getInt("messages"),
                        rs.getTimestamp("latest") == null
                                ? null
                                : rs.getTimestamp("latest").toInstant(),
                        rs.getString("latest_subject"),
                        rs.getString("reason"),
                        SenderTrust.canBeTrusted(rs.getString("sender"))),
                userId);
    }

    /** Domains this user has accepted, newest first. */
    public List<TrustedSender> trustedSenders(UUID userId) {
        return jdbc.query("""
                select domain, note, created_at
                  from trusted_senders
                 where user_id = ?
                 order by created_at desc
                """,
                (rs, row) -> new TrustedSender(
                        rs.getString("domain"),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()),
                userId);
    }

    /**
     * Accepts a domain and releases everything held from it.
     *
     * <p>The messages go back to pending rather than being parsed here, so they
     * take exactly the same path as any other alert — including duplicate
     * detection, which matters because a held message may well have already
     * arrived by SMS.
     *
     * @return how many messages were released
     * @throws IllegalArgumentException if the domain is one that may never be trusted
     */
    @Transactional
    public int trustSender(UUID userId, String rawDomain, String note) {
        String domain = SenderTrust.domainOf(rawDomain)
                .orElseThrow(() -> new IllegalArgumentException(
                        "That does not look like a sender we can recognise."));

        if (!SenderTrust.canBeTrusted(domain)) {
            // Refused rather than obeyed. Trusting a consumer mail provider
            // means trusting everyone who has an address there, which is the
            // whole hole this closes.
            throw new IllegalArgumentException(domain
                    + " is a personal mail provider, so anyone can send from it. "
                    + "Alerts from there will always need confirming by hand.");
        }

        jdbc.update("""
                insert into trusted_senders (user_id, domain, note)
                values (?, ?, ?)
                on conflict (user_id, domain) do nothing
                """, userId, domain, note);

        return jdbc.update("""
                update raw_messages
                   set status = 'pending', quarantine_reason = null, parsed_at = null
                 where user_id = ? and status = 'quarantined'
                   and (lower(sender) like ? or lower(sender) like ?)
                """, userId, "%@" + domain, "%." + domain);
    }

    /** Withdraws trust. Messages already recorded are left alone. */
    @Transactional
    public boolean untrustSender(UUID userId, String rawDomain) {
        String domain = SenderTrust.domainOf(rawDomain).orElse(null);
        if (domain == null) {
            return false;
        }
        return jdbc.update("delete from trusted_senders where user_id = ? and domain = ?",
                userId, domain) > 0;
    }

    /** Discards everything held from a sender, without trusting it. */
    @Transactional
    public int discardHeld(UUID userId, String rawSender) {
        return jdbc.update("""
                update raw_messages
                   set status = 'ignored', quarantine_reason = null, parsed_at = now()
                 where user_id = ? and status = 'quarantined' and sender = ?
                """, userId, rawSender);
    }

    /**
     * A sender whose messages are being held.
     *
     * @param canBeTrusted whether accepting it is even offered; false for a
     *                     consumer mail provider, so the interface can explain
     *                     rather than present a button that will be refused
     */
    public record HeldSender(String sender, String domain, int messages, Instant latest,
            String latestSubject, String reason, boolean canBeTrusted) {
    }

    /** A domain the user has accepted. */
    public record TrustedSender(String domain, String note, Instant since) {
    }
}
