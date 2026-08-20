package com.expensetracker.api.transactions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Filters for the transaction list, compiled into a parameterised WHERE clause.
 *
 * <p>Values are always bound, never interpolated, so a search term cannot
 * change the shape of the query.
 */
public record TransactionFilter(
        Instant from,
        Instant to,
        UUID accountId,
        UUID categoryId,
        UUID merchantId,
        String kind,
        String search,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Boolean includeExcluded,
        int limit,
        int offset) {

    private static final int MAX_LIMIT = 200;

    /** Caps page size so one client cannot pull the whole table in a request. */
    public TransactionFilter {
        limit = limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        offset = Math.max(offset, 0);
    }

    /** Builds the WHERE fragment and the matching argument list, in order. */
    public Compiled compile(UUID userId) {
        StringBuilder sql = new StringBuilder("""
                where t.user_id = ?
                  and t.deleted_at is null
                  and t.merged_into_id is null
                """);
        List<Object> args = new ArrayList<>();
        args.add(userId);

        if (from != null) {
            sql.append(" and t.occurred_at >= ?\n");
            args.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" and t.occurred_at <= ?\n");
            args.add(java.sql.Timestamp.from(to));
        }
        if (accountId != null) {
            sql.append(" and t.account_id = ?\n");
            args.add(accountId);
        }
        if (categoryId != null) {
            sql.append(" and t.category_id = ?\n");
            args.add(categoryId);
        }
        if (merchantId != null) {
            sql.append(" and t.merchant_id = ?\n");
            args.add(merchantId);
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(" and t.kind = ?::transaction_kind\n");
            args.add(TransactionKind.from(kind).dbValue());
        }
        if (minAmount != null) {
            sql.append(" and t.amount >= ?\n");
            args.add(minAmount);
        }
        if (maxAmount != null) {
            sql.append(" and t.amount <= ?\n");
            args.add(maxAmount);
        }
        if (!Boolean.TRUE.equals(includeExcluded)) {
            sql.append(" and t.is_excluded = false\n");
        }
        if (search != null && !search.isBlank()) {
            // Wildcards live in the bound value, so the pattern stays data.
            sql.append(" and (t.description ilike ? or m.name ilike ? or t.notes ilike ?)\n");
            String pattern = "%" + search.trim() + "%";
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        return new Compiled(sql.toString(), args);
    }

    public record Compiled(String where, List<Object> args) {
        public Object[] argArray() {
            return args.toArray();
        }
    }
}
