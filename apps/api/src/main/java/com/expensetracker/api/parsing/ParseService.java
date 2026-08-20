package com.expensetracker.api.parsing;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
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
    private final TransactionTemplate tx;

    public ParseService(JdbcTemplate jdbc, ParserRules rules, TransactionService transactions,
            UserSettings settings, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.transactions = transactions;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** One stored message, as much as parsing needs of it. */
    private record Pending(UUID id, UUID connectionId, String sender, String subject,
            String body, Instant receivedAt) {
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
            return new ParseResult(0, 0, 0, 0, 0);
        }

        ZoneId zone = settings.zoneOf(userId);
        List<Pending> pending = pending(userId);

        int imported = 0;
        int merged = 0;
        int failed = 0;
        int ignored = 0;

        for (Pending message : pending) {
            Outcome outcome = parseOne(userId, message, loaded, zone);
            switch (outcome) {
                case IMPORTED -> imported++;
                case MERGED -> merged++;
                case IGNORED -> ignored++;
                case FAILED -> failed++;
            }
        }
        return new ParseResult(pending.size(), imported, merged, ignored, failed);
    }

    private enum Outcome { IMPORTED, MERGED, IGNORED, FAILED }

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
    private Outcome parseOne(UUID userId, Pending message, List<ParserRule> loaded, ZoneId zone) {
        try {
            return tx.execute(status -> {
                ParsedAlert parsed = AlertParser.parse(message.sender(), message.subject(),
                        message.body(), message.receivedAt(), zone, loaded);

                if (!parsed.isSuccess()) {
                    markFailed(userId, message.id(), parsed);
                    return Outcome.FAILED;
                }

                TransactionService.Created created = transactions.create(userId,
                        toRequest(userId, message, parsed),
                        Origin.message(message.connectionId(), message.id()));

                markParsed(userId, message.id(), parsed);
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
                select r.id, r.connection_id, r.sender, r.subject, r.body, r.received_at
                  from raw_messages r
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
                                : rs.getTimestamp("received_at").toInstant()),
                userId, DEFAULT_LIMIT);
    }

    private void markParsed(UUID userId, UUID messageId, ParsedAlert parsed) {
        jdbc.update("""
                update raw_messages
                   set status = 'parsed', parser_rule_id = ?, parse_error = null,
                       parsed_at = now(), parse_notes = ?
                 where id = ? and user_id = ?
                """,
                parsed.ruleId(),
                parsed.dateExact() ? null : "Used the date this alert arrived; "
                        + "the message did not carry a date we could read.",
                messageId, userId);
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
                  count(*) filter (where status = 'pending') as pending,
                  count(*) filter (where status = 'failed')  as failed,
                  count(*) filter (where status = 'parsed')  as parsed
                from raw_messages where user_id = ?
                """,
                (rs, row) -> new ParseQueue(rs.getInt("pending"), rs.getInt("failed"),
                        rs.getInt("parsed")),
                userId);
    }
}
