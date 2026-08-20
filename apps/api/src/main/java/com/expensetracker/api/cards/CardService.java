package com.expensetracker.api.cards;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.profile.UserSettings;

/**
 * Credit cards: limits, billing cycles and what is actually owed.
 *
 * <p>Live outstanding is derived from the ledger rather than stored, for the
 * same reason account balances are: a stored figure drifts the moment a
 * transaction is edited, deleted or merged away as a duplicate.
 */
@Service
public class CardService {

    private static final String SELECT = """
            select a.id, a.name, a.last4, a.currency, a.is_archived,
                   account_balance(a.id) as balance,
                   d.credit_limit, d.billing_day, d.due_day,
                   d.outstanding as statement_balance, d.minimum_due, d.last_statement_at
            from accounts a
            left join credit_card_details d on d.account_id = a.id
            where a.user_id = ? and a.type = 'credit_card'
            """;

    private final JdbcTemplate jdbc;
    private final UserSettings settings;

    public CardService(JdbcTemplate jdbc, UserSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public List<CardView> list(UUID userId, boolean includeArchived) {
        LocalDate today = settings.today(userId);
        ZoneId zone = settings.zoneOf(userId);

        List<Row> rows = jdbc.query(
                SELECT + " and (? or a.is_archived = false) order by a.sort_order, a.name",
                CardService::mapRow, userId, includeArchived);

        List<CardView> views = new ArrayList<>(rows.size());
        for (Row row : rows) {
            views.add(evaluate(userId, row, today, zone));
        }
        return views;
    }

    public CardView get(UUID userId, UUID accountId) {
        Row row = find(userId, accountId).orElseThrow(CardService::notACard);
        return evaluate(userId, row, settings.today(userId), settings.zoneOf(userId));
    }

    /**
     * Upsert, because the detail row is an optional extension of the account
     * rather than something the user creates separately — they just fill in
     * what their bank told them.
     */
    @Transactional
    public CardView save(UUID userId, UUID accountId, CardRequest request) {
        find(userId, accountId).orElseThrow(CardService::notACard);

        if (request.lastStatementAt() != null
                && request.lastStatementAt().isAfter(settings.today(userId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A statement cannot be dated in the future.");
        }
        if (request.minimumDue() != null && request.statementBalance() != null
                && request.minimumDue().compareTo(request.statementBalance()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The minimum due cannot be more than the statement balance.");
        }

        jdbc.update("""
                insert into credit_card_details (
                    account_id, user_id, credit_limit, billing_day, due_day,
                    outstanding, minimum_due, last_statement_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (account_id) do update set
                    credit_limit = excluded.credit_limit,
                    billing_day = excluded.billing_day,
                    due_day = excluded.due_day,
                    outstanding = excluded.outstanding,
                    minimum_due = excluded.minimum_due,
                    last_statement_at = excluded.last_statement_at
                """,
                accountId, userId,
                request.creditLimit(), request.billingDay(), request.dueDay(),
                request.statementBalance(), request.minimumDue(), request.lastStatementAt());

        return get(userId, accountId);
    }

    @Transactional
    public void clear(UUID userId, UUID accountId) {
        find(userId, accountId).orElseThrow(CardService::notACard);
        jdbc.update("delete from credit_card_details where user_id = ? and account_id = ?",
                userId, accountId);
    }

    // ---- evaluation --------------------------------------------------------

    private CardView evaluate(UUID userId, Row row, LocalDate today, ZoneId zone) {
        // A credit card balance is negative while money is owed, so the amount
        // outstanding is the balance flipped — floored at zero, because paying
        // ahead leaves a credit rather than a negative debt.
        BigDecimal outstanding = row.balance.negate().max(BigDecimal.ZERO);

        BigDecimal available = null;
        Double utilisation = null;
        if (row.creditLimit != null && row.creditLimit.signum() > 0) {
            available = row.creditLimit.subtract(outstanding);
            utilisation = Math.round(outstanding
                    .divide(row.creditLimit, 4, RoundingMode.HALF_UP)
                    .doubleValue() * 1000) / 10.0;
        }

        CardCycle cycle = row.billingDay == null || row.dueDay == null
                ? null
                : CardCycle.of(row.billingDay, row.dueDay, today);

        BigDecimal currentSpend = cycle == null
                ? null
                : sum(userId, row.id, "debit", cycle.statementDate(), cycle.nextStatement(), zone);

        // Counted from the day *after* the statement, never the day itself. A
        // payment made on the statement date may already be inside the balance
        // the bank quoted, and counting it twice would say "paid" when it is
        // not — an error that costs a late fee. Over-reporting a debt only
        // prompts the user to check.
        BigDecimal paid = row.lastStatementAt == null
                ? null
                : sum(userId, row.id, "credit", row.lastStatementAt.plusDays(1), null, zone);

        BigDecimal remainingDue = null;
        BigDecimal minimumRemaining = null;
        if (row.statementBalance != null && paid != null) {
            remainingDue = row.statementBalance.subtract(paid).max(BigDecimal.ZERO);
            BigDecimal minimum = row.minimumDue == null ? BigDecimal.ZERO : row.minimumDue;
            minimumRemaining = minimum.subtract(paid).max(BigDecimal.ZERO);
        }

        return new CardView(
                row.id, row.name, row.last4, row.currency, row.isArchived,
                row.creditLimit, outstanding, available, utilisation,
                row.billingDay, row.dueDay,
                cycle == null ? null : cycle.statementDate(),
                cycle == null ? null : cycle.dueDate(),
                cycle == null ? null : cycle.nextStatement(),
                cycle == null ? null : cycle.daysUntilDue(today),
                row.statementBalance, row.minimumDue, row.lastStatementAt,
                currentSpend, paid, remainingDue, minimumRemaining,
                status(outstanding, remainingDue, minimumRemaining, cycle, today));
    }

    /**
     * Deliberately says {@code tracking} rather than guessing when the bank's
     * side is unknown. Claiming a card is clear because no statement was
     * entered would be worse than admitting there is nothing to compare against.
     */
    private String status(BigDecimal outstanding, BigDecimal remainingDue,
            BigDecimal minimumRemaining, CardCycle cycle, LocalDate today) {

        if (remainingDue == null) {
            return outstanding.signum() == 0 ? "clear" : "tracking";
        }
        if (remainingDue.signum() == 0) {
            return "paid";
        }
        if (cycle != null && cycle.isOverdue(today) && minimumRemaining.signum() > 0) {
            return "overdue";
        }
        return minimumRemaining.signum() == 0 ? "minimum_met" : "due";
    }

    /**
     * Payments and spend are counted regardless of {@code is_excluded}: that
     * flag keeps a row out of spending analytics, but the money still moved.
     * Transfer legs count too — paying a card off is a transfer.
     */
    private BigDecimal sum(UUID userId, UUID accountId, String direction,
            LocalDate from, LocalDate toExclusive, ZoneId zone) {

        StringBuilder sql = new StringBuilder("""
                select coalesce(sum(t.amount), 0)
                from transactions t
                where t.user_id = ? and t.account_id = ?
                  and t.direction = ?::transaction_direction
                  and t.deleted_at is null
                  and t.merged_into_id is null
                  and t.occurred_at >= ?
                """);

        List<Object> args = new ArrayList<>(List.of(
                userId, accountId, direction,
                Timestamp.from(from.atStartOfDay(zone).toInstant())));

        if (toExclusive != null) {
            sql.append(" and t.occurred_at < ?");
            args.add(Timestamp.from(toExclusive.atStartOfDay(zone).toInstant()));
        }

        BigDecimal total = jdbc.queryForObject(sql.toString(), BigDecimal.class, args.toArray());
        return total == null ? BigDecimal.ZERO : total;
    }

    // ---- helpers -----------------------------------------------------------

    private Optional<Row> find(UUID userId, UUID accountId) {
        return jdbc.query(SELECT + " and a.id = ?", CardService::mapRow, userId, accountId)
                .stream().findFirst();
    }

    /**
     * One message for "no such account" and "not a credit card" alike: card
     * details on a savings account are meaningless, and distinguishing the two
     * would confirm that an account id exists on someone else's books.
     */
    private static ResponseStatusException notACard() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit card not found");
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("last4"),
                rs.getString("currency"),
                rs.getBoolean("is_archived"),
                rs.getBigDecimal("balance"),
                rs.getBigDecimal("credit_limit"),
                (Integer) rs.getObject("billing_day"),
                (Integer) rs.getObject("due_day"),
                rs.getBigDecimal("statement_balance"),
                rs.getBigDecimal("minimum_due"),
                rs.getObject("last_statement_at", LocalDate.class));
    }

    private record Row(
            UUID id, String name, String last4, String currency, boolean isArchived,
            BigDecimal balance, BigDecimal creditLimit, Integer billingDay, Integer dueDay,
            BigDecimal statementBalance, BigDecimal minimumDue, LocalDate lastStatementAt) {
    }
}
