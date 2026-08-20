package com.expensetracker.api.budgets;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
 * Budgets, and how much of each one is left.
 *
 * <p>Spend is always computed from the ledger on read. Storing a running total
 * would drift the moment a transaction is edited, deleted, merged as a
 * duplicate or re-categorised — and a budget that quietly disagrees with the
 * transaction list is worse than no budget at all.
 */
@Service
public class BudgetService {

    /**
     * Everything that counts against a budget.
     *
     * <p>Excludes transfers (moving your own money is not spending), income,
     * rows the user marked as excluded, soft-deleted rows, and rows merged away
     * as duplicates — counting a merged duplicate would double the spend.
     */
    private static final String SPEND_WHERE = """
             where t.user_id = ?
               and t.kind = 'expense'
               and t.deleted_at is null
               and t.merged_into_id is null
               and t.is_excluded = false
               and t.occurred_at >= ?
               and t.occurred_at < ?
            """;

    /**
     * A budget on a parent category covers its children too. Someone who
     * budgets "Food" means groceries and dining, not an empty parent bucket.
     */
    private static final String CATEGORY_TREE = """
            with recursive tree as (
                select id from categories where user_id = ? and id = ?
                union all
                select c.id from categories c join tree on c.parent_id = tree.id
            )
            """;

    private static final String SELECT = """
            select b.id, b.name, b.category_id, c.name as category_name,
                   b.amount, b.currency, b.period::text as period,
                   b.starts_on, b.ends_on, b.rollover, b.alert_thresholds, b.is_active
            from budgets b
            left join categories c on c.id = b.category_id
            """;

    private final JdbcTemplate jdbc;
    private final UserSettings settings;

    public BudgetService(JdbcTemplate jdbc, UserSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    public List<BudgetView> list(UUID userId, boolean includeInactive) {
        LocalDate today = settings.today(userId);
        ZoneId zone = settings.zoneOf(userId);

        List<Row> rows = jdbc.query(SELECT + """
                where b.user_id = ?
                  and (? or b.is_active = true)
                order by b.is_active desc, c.name nulls first, b.amount desc
                """, BudgetService::mapRow, userId, includeInactive);

        List<BudgetView> views = new ArrayList<>(rows.size());
        for (Row row : rows) {
            views.add(evaluate(userId, row, today, zone));
        }
        return views;
    }

    public BudgetView get(UUID userId, UUID id) {
        Row row = find(userId, id).orElseThrow(BudgetService::notFound);
        return evaluate(userId, row, settings.today(userId), settings.zoneOf(userId));
    }

    @Transactional
    public BudgetView create(UUID userId, BudgetRequest request) {
        LocalDate today = settings.today(userId);
        validate(userId, request, today);

        UUID id = jdbc.queryForObject("""
                insert into budgets (
                    user_id, category_id, name, amount, currency, period,
                    starts_on, ends_on, rollover, alert_thresholds, is_active)
                values (?, ?, ?, ?, ?, ?::budget_period, ?, ?, ?, ?::smallint[], ?)
                returning id
                """,
                UUID.class,
                userId,
                request.categoryId(),
                request.name(),
                request.amount(),
                request.currencyOrDefault(settings.baseCurrency(userId)),
                request.resolvedPeriod().dbValue(),
                request.resolvedStartsOn(today),
                request.endsOn(),
                request.rolloverOrDefault(),
                thresholdArray(request.resolvedThresholds()),
                request.activeOrDefault());

        return get(userId, id);
    }

    @Transactional
    public BudgetView update(UUID userId, UUID id, BudgetRequest request) {
        find(userId, id).orElseThrow(BudgetService::notFound);

        LocalDate today = settings.today(userId);
        validate(userId, request, today);

        jdbc.update("""
                update budgets
                   set category_id = ?, name = ?, amount = ?, currency = ?,
                       period = ?::budget_period, starts_on = ?, ends_on = ?,
                       rollover = ?, alert_thresholds = ?::smallint[], is_active = ?
                 where id = ? and user_id = ?
                """,
                request.categoryId(),
                request.name(),
                request.amount(),
                request.currencyOrDefault(settings.baseCurrency(userId)),
                request.resolvedPeriod().dbValue(),
                request.resolvedStartsOn(today),
                request.endsOn(),
                request.rolloverOrDefault(),
                thresholdArray(request.resolvedThresholds()),
                request.activeOrDefault(),
                id, userId);

        return get(userId, id);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        int deleted = jdbc.update("delete from budgets where id = ? and user_id = ?", id, userId);
        if (deleted == 0) {
            throw notFound();
        }
    }

    // ---- evaluation --------------------------------------------------------

    private BudgetView evaluate(UUID userId, Row row, LocalDate today, ZoneId zone) {
        BudgetWindow window = BudgetWindow.current(row.period, row.startsOn, today);

        BigDecimal spent = spendBetween(userId, row.categoryId, window.start(),
                window.endExclusive(), zone);

        // Rollover is cumulative from the start date: everything budgeted so
        // far, minus everything spent so far. Floored at zero so an overspend
        // does not become a debt the user can never see the origin of.
        BigDecimal carried = BigDecimal.ZERO;
        if (row.rollover && window.index() > 0) {
            BigDecimal allowed = row.amount.multiply(BigDecimal.valueOf(window.index()));
            BigDecimal before = spendBetween(userId, row.categoryId, row.startsOn,
                    window.start(), zone);
            carried = allowed.subtract(before).max(BigDecimal.ZERO);
        }

        BigDecimal limit = row.amount.add(carried);
        BigDecimal remaining = limit.subtract(spent);

        double percent = limit.signum() == 0
                ? 0
                : spent.divide(limit, 4, RoundingMode.HALF_UP).doubleValue() * 100;
        percent = Math.round(percent * 10) / 10.0;

        return new BudgetView(
                row.id, row.name, row.categoryId, row.categoryName,
                row.amount, row.currency, row.period.dbValue(),
                row.startsOn, row.endsOn, row.rollover, row.thresholds, row.isActive,
                window.start(), window.endInclusive(),
                window.daysRemaining(today), window.totalDays(),
                spent, carried, limit, remaining, percent,
                project(spent, window, today),
                status(row, window, today, percent));
    }

    /**
     * What this pace would spend by the end of the window.
     *
     * <p>Only meaningful once a day has actually elapsed; extrapolating from a
     * few hours would show an alarming number every morning.
     */
    private BigDecimal project(BigDecimal spent, BudgetWindow window, LocalDate today) {
        if (!window.contains(today)) {
            return spent;
        }
        long elapsed = java.time.temporal.ChronoUnit.DAYS.between(window.start(), today) + 1;
        if (elapsed < 2 || elapsed >= window.totalDays()) {
            return spent;
        }
        return spent.multiply(BigDecimal.valueOf(window.totalDays()))
                .divide(BigDecimal.valueOf(elapsed), 2, RoundingMode.HALF_UP);
    }

    /**
     * Uses the user's own thresholds rather than a fixed rule, because the
     * point of setting them is to decide when you want to be told.
     */
    private String status(Row row, BudgetWindow window, LocalDate today, double percent) {
        if (today.isBefore(window.start())) {
            return "upcoming";
        }
        if (row.endsOn != null && today.isAfter(row.endsOn)) {
            return "ended";
        }
        if (percent >= 100) {
            return "over";
        }

        int warnAt = row.thresholds.stream().filter(t -> t < 100).max(Integer::compareTo).orElse(80);
        return percent >= warnAt ? "warning" : "on_track";
    }

    private BigDecimal spendBetween(UUID userId, UUID categoryId, LocalDate from,
            LocalDate toExclusive, ZoneId zone) {
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = toExclusive.atStartOfDay(zone).toInstant();

        String sql = categoryId == null
                ? "select coalesce(sum(t.amount), 0) from transactions t" + SPEND_WHERE
                : CATEGORY_TREE + "select coalesce(sum(t.amount), 0) from transactions t"
                        + SPEND_WHERE + " and t.category_id in (select id from tree)";

        Object[] args = categoryId == null
                ? new Object[] { userId, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end) }
                : new Object[] { userId, categoryId, userId,
                        java.sql.Timestamp.from(start), java.sql.Timestamp.from(end) };

        BigDecimal total = jdbc.queryForObject(sql, BigDecimal.class, args);
        return total == null ? BigDecimal.ZERO : total;
    }

    // ---- helpers -----------------------------------------------------------

    private void validate(UUID userId, BudgetRequest request, LocalDate today) {
        if (request.categoryId() != null) {
            Long count = jdbc.queryForObject(
                    "select count(*) from categories where user_id = ? and id = ?",
                    Long.class, userId, request.categoryId());
            if (count == null || count == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
            }
        }

        LocalDate startsOn = request.resolvedStartsOn(today);
        if (request.endsOn() != null && !request.endsOn().isAfter(startsOn)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The end date has to be after the start date.");
        }
    }

    /**
     * {@code smallint[]} has no JDBC equivalent, so the values go in as a
     * Postgres array literal rather than being interpolated into the SQL.
     */
    private String thresholdArray(List<Integer> thresholds) {
        StringBuilder literal = new StringBuilder("{");
        for (int i = 0; i < thresholds.size(); i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(thresholds.get(i).intValue());
        }
        return literal.append('}').toString();
    }

    private Optional<Row> find(UUID userId, UUID id) {
        return jdbc.query(SELECT + " where b.user_id = ? and b.id = ?",
                BudgetService::mapRow, userId, id).stream().findFirst();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget not found");
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                BudgetPeriod.from(rs.getString("period")),
                rs.getObject("starts_on", LocalDate.class),
                rs.getObject("ends_on", LocalDate.class),
                rs.getBoolean("rollover"),
                readThresholds(rs),
                rs.getBoolean("is_active"));
    }

    private static List<Integer> readThresholds(ResultSet rs) throws SQLException {
        Array array = rs.getArray("alert_thresholds");
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        List<Integer> thresholds = new ArrayList<>(values.length);
        for (Object value : values) {
            thresholds.add(((Number) value).intValue());
        }
        return thresholds;
    }

    private record Row(
            UUID id, String name, UUID categoryId, String categoryName,
            BigDecimal amount, String currency, BudgetPeriod period,
            LocalDate startsOn, LocalDate endsOn, boolean rollover,
            List<Integer> thresholds, boolean isActive) {
    }
}
