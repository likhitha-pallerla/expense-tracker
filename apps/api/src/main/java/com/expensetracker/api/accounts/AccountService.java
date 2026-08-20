package com.expensetracker.api.accounts;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Account CRUD.
 *
 * <p>Every statement is scoped by {@code user_id}. The API connects as a role
 * that bypasses RLS, so an unscoped query here would expose another user's
 * data even though the database policies look correct.
 */
@Service
public class AccountService {

    private static final RowMapper<AccountView> MAPPER = (rs, rowNum) -> new AccountView(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("type"),
            rs.getString("currency"),
            rs.getString("last4"),
            rs.getBigDecimal("opening_balance"),
            rs.getBigDecimal("balance"),
            rs.getBoolean("is_archived"),
            rs.getInt("sort_order"),
            rs.getTimestamp("created_at").toInstant());

    private static final String SELECT = """
            select a.id, a.name, a.type::text as type, a.currency, a.last4,
                   a.opening_balance, account_balance(a.id) as balance,
                   a.is_archived, a.sort_order, a.created_at
            from accounts a
            """;

    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Archived accounts are hidden by default so old cards stop cluttering pickers. */
    public List<AccountView> list(UUID userId, boolean includeArchived) {
        String sql = SELECT + """
                where a.user_id = ?
                  and (? or a.is_archived = false)
                order by a.is_archived, a.sort_order, a.name
                """;
        return jdbc.query(sql, MAPPER, userId, includeArchived);
    }

    public AccountView get(UUID userId, UUID accountId) {
        return find(userId, accountId).orElseThrow(AccountService::notFound);
    }

    private Optional<AccountView> find(UUID userId, UUID accountId) {
        String sql = SELECT + "where a.user_id = ? and a.id = ?";
        return jdbc.query(sql, MAPPER, userId, accountId).stream().findFirst();
    }

    @Transactional
    public AccountView create(UUID userId, AccountRequest request) {
        AccountType type = AccountType.from(request.type());

        UUID id = jdbc.queryForObject("""
                insert into accounts (user_id, name, type, currency, last4, opening_balance, sort_order)
                values (?, ?, ?::account_type, ?, ?, ?, coalesce(?, (
                    select coalesce(max(sort_order), -1) + 1 from accounts where user_id = ?
                )))
                returning id
                """,
                UUID.class,
                userId,
                request.normalizedName(),
                type.dbValue(),
                request.currencyOrDefault(),
                request.last4(),
                request.openingBalanceOrZero(),
                request.sortOrder(),
                userId);

        return get(userId, id);
    }

    /**
     * Full replace. Currency is deliberately not updatable: existing
     * transactions were recorded in the original currency, and silently
     * relabelling them would corrupt every historical total.
     */
    @Transactional
    public AccountView update(UUID userId, UUID accountId, AccountRequest request) {
        AccountType type = AccountType.from(request.type());

        int updated = jdbc.update("""
                update accounts
                   set name = ?,
                       type = ?::account_type,
                       last4 = ?,
                       opening_balance = ?,
                       is_archived = coalesce(?, is_archived),
                       sort_order = coalesce(?, sort_order)
                 where user_id = ? and id = ?
                """,
                request.normalizedName(),
                type.dbValue(),
                request.last4(),
                request.openingBalanceOrZero(),
                request.isArchived(),
                request.sortOrder(),
                userId,
                accountId);

        if (updated == 0) {
            throw notFound();
        }
        return get(userId, accountId);
    }

    /**
     * Archives instead of deleting when an account still carries transactions.
     *
     * <p>Deleting would set {@code account_id} to null on every historical row
     * (the FK is ON DELETE SET NULL), orphaning spend that the user still needs
     * in reports. Archiving keeps the history intact and hides the account.
     */
    @Transactional
    public DeleteResult delete(UUID userId, UUID accountId) {
        if (find(userId, accountId).isEmpty()) {
            throw notFound();
        }

        Long inUse = jdbc.queryForObject("""
                select count(*) from transactions
                where user_id = ? and account_id = ? and deleted_at is null
                """, Long.class, userId, accountId);

        if (inUse != null && inUse > 0) {
            jdbc.update("update accounts set is_archived = true where user_id = ? and id = ?",
                    userId, accountId);
            return new DeleteResult(false, inUse);
        }

        jdbc.update("delete from accounts where user_id = ? and id = ?", userId, accountId);
        return new DeleteResult(true, 0L);
    }

    /** Confirms an account belongs to the caller before it is referenced elsewhere. */
    public void requireOwned(UUID userId, UUID accountId) {
        Long count = jdbc.queryForObject(
                "select count(*) from accounts where user_id = ? and id = ?",
                Long.class, userId, accountId);
        if (count == null || count == 0) {
            throw notFound();
        }
    }

    public BigDecimal totalBalance(UUID userId) {
        BigDecimal total = jdbc.queryForObject("""
                select coalesce(sum(account_balance(a.id)), 0)
                from accounts a
                where a.user_id = ? and a.is_archived = false
                """, BigDecimal.class, userId);
        return total == null ? BigDecimal.ZERO : total;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
    }

    /** Tells the client whether the row was removed or merely hidden. */
    public record DeleteResult(boolean deleted, long transactionCount) {
    }
}
