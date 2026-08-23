package com.expensetracker.api.goals;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.profile.UserSettings;

/**
 * Savings goals and how they are going.
 *
 * <p>Nothing here stores a running total. What has been saved is summed from
 * the contribution ledger every time it is read, for the same reason account
 * balances and budget spend are: a cached total is one missed update away from
 * contradicting the rows underneath it, and a goal that disagrees with its own
 * deposit history is worse than no goal at all.
 */
@Service
public class GoalService {

    private static final String SELECT = """
            select g.id, g.name, g.target_amount, g.currency, g.target_date,
                   g.monthly_target, g.account_id, a.name as account_name,
                   g.notes, g.status::text as status, g.achieved_at, g.created_at
            from goals g
            left join accounts a on a.id = g.account_id
            """;

    /**
     * Both derived figures in one pass.
     *
     * <p>{@code min(occurred_on)} is what makes an honest pace possible: it is
     * when saving started, which is usually not when the goal was created.
     */
    private static final String TOTALS = """
            select goal_id, sum(amount) as saved, min(occurred_on) as first_on
            from goal_contributions
            where user_id = ?
            group by goal_id
            """;

    private static final String CONTRIBUTIONS = """
            select id, goal_id, amount, occurred_on, note, transaction_id, created_at
            from goal_contributions
            where user_id = ?
            """;

    private static final String CONTRIBUTION_ORDER =
            " order by occurred_on desc, created_at desc";

    private final JdbcTemplate jdbc;
    private final UserSettings settings;

    public GoalService(JdbcTemplate jdbc, UserSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /**
     * @param includeClosed      cancelled goals are hidden by default; achieved
     *                           ones are not, because seeing what you finished
     *                           is most of the reason to keep using this
     * @param withContributions  loads every goal's history in one extra query
     *                           rather than one per goal; off by default so a
     *                           caller that only wants progress bars does not
     *                           pay for a ledger it will not draw
     */
    public List<GoalView> list(UUID userId, boolean includeClosed, boolean withContributions) {
        LocalDate today = settings.today(userId);
        Map<UUID, Totals> totals = totals(userId);
        Map<UUID, List<GoalContribution>> history = withContributions
                ? allContributions(userId)
                : Map.of();

        List<Row> rows = jdbc.query(SELECT + """
                where g.user_id = ?
                  and (? or g.status <> 'cancelled')
                order by
                  case g.status when 'active' then 0 when 'paused' then 1
                                when 'achieved' then 2 else 3 end,
                  g.target_date nulls last,
                  g.created_at
                """, GoalService::mapRow, userId, includeClosed);

        List<GoalView> views = new ArrayList<>(rows.size());
        for (Row row : rows) {
            views.add(view(
                    row, totals.get(row.id()), today,
                    history.getOrDefault(row.id(), List.of())));
        }
        return views;
    }

    public GoalView get(UUID userId, UUID id) {
        Row row = find(userId, id).orElseThrow(GoalService::notFound);
        return view(row, totals(userId).get(id), settings.today(userId), contributions(userId, id));
    }

    @Transactional
    public GoalView create(UUID userId, GoalRequest request) {
        validate(userId, request);

        UUID id = jdbc.queryForObject("""
                insert into goals (
                    user_id, name, target_amount, currency, target_date,
                    monthly_target, account_id, notes, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::goal_status)
                returning id
                """,
                UUID.class,
                userId,
                request.name().trim(),
                request.targetAmount(),
                request.currencyOrDefault(settings.baseCurrency(userId)),
                request.targetDate(),
                request.monthlyTarget(),
                request.accountId(),
                request.notes(),
                request.resolvedStatus().dbValue());

        return get(userId, id);
    }

    @Transactional
    public GoalView update(UUID userId, UUID id, GoalRequest request) {
        find(userId, id).orElseThrow(GoalService::notFound);
        validate(userId, request);

        jdbc.update("""
                update goals
                   set name = ?, target_amount = ?, currency = ?, target_date = ?,
                       monthly_target = ?, account_id = ?, notes = ?,
                       status = ?::goal_status
                 where id = ? and user_id = ?
                """,
                request.name().trim(),
                request.targetAmount(),
                request.currencyOrDefault(settings.baseCurrency(userId)),
                request.targetDate(),
                request.monthlyTarget(),
                request.accountId(),
                request.notes(),
                request.resolvedStatus().dbValue(),
                id, userId);

        settleAchievement(userId, id);
        return get(userId, id);
    }

    /**
     * Deleting a goal takes its contributions with it, by the foreign key.
     *
     * <p>That is the right call here rather than a soft delete: the ledger only
     * ever recorded intent to save, never actual money, so nothing is lost that
     * the transaction list does not still hold.
     */
    @Transactional
    public void delete(UUID userId, UUID id) {
        int deleted = jdbc.update("delete from goals where id = ? and user_id = ?", id, userId);
        if (deleted == 0) throw notFound();
    }

    @Transactional
    public GoalView contribute(UUID userId, UUID goalId, GoalContributionRequest request) {
        Row goal = find(userId, goalId).orElseThrow(GoalService::notFound);

        if (request.amount().signum() == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A contribution of zero records nothing.");
        }
        if (GoalStatus.CANCELLED.dbValue().equals(goal.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This goal was cancelled. Reactivate it before adding to it.");
        }

        LocalDate today = settings.today(userId);
        LocalDate on = request.resolvedDate(today);
        if (on.isAfter(today)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "That date is in the future.");
        }

        jdbc.update("""
                insert into goal_contributions (user_id, goal_id, amount, occurred_on, note)
                values (?, ?, ?, ?, ?)
                """, userId, goalId, request.amount(), on, request.note());

        settleAchievement(userId, goalId);
        return get(userId, goalId);
    }

    @Transactional
    public GoalView removeContribution(UUID userId, UUID goalId, UUID contributionId) {
        find(userId, goalId).orElseThrow(GoalService::notFound);

        int deleted = jdbc.update("""
                delete from goal_contributions
                 where id = ? and goal_id = ? and user_id = ?
                """, contributionId, goalId, userId);

        if (deleted == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No such contribution on this goal.");
        }

        settleAchievement(userId, goalId);
        return get(userId, goalId);
    }

    /**
     * Move a goal to achieved the moment the target is reached.
     *
     * <p>Only ever moves in that direction. Taking money back out later drops
     * the progress bar below 100%, but {@code achieved_at} stays put: reaching
     * the goal was a thing that happened on a particular day, and spending some
     * of it afterwards does not undo the day.
     *
     * <p>Paused and cancelled goals are left alone — a status the user chose is
     * not ours to overwrite.
     */
    private void settleAchievement(UUID userId, UUID goalId) {
        jdbc.update("""
                update goals g
                   set status = 'achieved'::goal_status,
                       achieved_at = coalesce(g.achieved_at, now())
                 where g.id = ? and g.user_id = ?
                   and g.status = 'active'
                   and goal_saved(g.id) >= g.target_amount
                """, goalId, userId);
    }

    private void validate(UUID userId, GoalRequest request) {
        if (request.accountId() != null) {
            Integer found = jdbc.queryForObject(
                    "select count(*) from accounts where id = ? and user_id = ?",
                    Integer.class, request.accountId(), userId);
            if (found == null || found == 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "That account does not exist.");
            }
        }

        // A target date in the past is allowed on update — goals get missed,
        // and refusing to save one would trap the user in an unfixable record.
        // On create it is almost certainly a typo.
        if (request.monthlyTarget() != null
                && request.monthlyTarget().compareTo(request.targetAmount()) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The monthly plan is larger than the whole goal.");
        }
    }

    private Map<UUID, Totals> totals(UUID userId) {
        Map<UUID, Totals> byGoal = new HashMap<>();
        jdbc.query(TOTALS, rs -> {
            byGoal.put(
                    rs.getObject("goal_id", UUID.class),
                    new Totals(
                            rs.getBigDecimal("saved"),
                            rs.getObject("first_on", LocalDate.class)));
        }, userId);
        return byGoal;
    }

    private List<GoalContribution> contributions(UUID userId, UUID goalId) {
        return jdbc.query(CONTRIBUTIONS + " and goal_id = ?" + CONTRIBUTION_ORDER,
                GoalService::mapContribution, userId, goalId);
    }

    /** Every goal's history in one query, so a list of goals is not N+1. */
    private Map<UUID, List<GoalContribution>> allContributions(UUID userId) {
        Map<UUID, List<GoalContribution>> byGoal = new HashMap<>();
        jdbc.query(CONTRIBUTIONS + CONTRIBUTION_ORDER, rs -> {
            byGoal.computeIfAbsent(rs.getObject("goal_id", UUID.class), key -> new ArrayList<>())
                    .add(mapContribution(rs, 0));
        }, userId);
        return byGoal;
    }

    private static GoalContribution mapContribution(ResultSet rs, int rowNum) throws SQLException {
        return new GoalContribution(
                rs.getObject("id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getObject("occurred_on", LocalDate.class),
                rs.getString("note"),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private GoalView view(Row row, Totals totals, LocalDate today, List<GoalContribution> items) {
        BigDecimal saved = totals == null ? BigDecimal.ZERO : totals.saved();
        LocalDate firstOn = totals == null ? null : totals.firstOn();

        GoalProgress progress = GoalProgress.of(
                row.targetAmount(), saved, row.targetDate(), firstOn, row.monthlyTarget(), today);

        GoalStatus status = GoalStatus.from(row.status());

        return new GoalView(
                row.id(), row.name(), row.targetAmount(), row.currency(), row.targetDate(),
                row.monthlyTarget(), row.accountId(), row.accountName(), row.notes(),
                row.status(), row.achievedAt(), row.createdAt(), progress, items,
                GoalView.headlineFor(progress, status, row.targetDate()));
    }

    private Optional<Row> find(UUID userId, UUID id) {
        return jdbc.query(SELECT + " where g.id = ? and g.user_id = ?",
                GoalService::mapRow, id, userId).stream().findFirst();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such goal.");
    }

    private static Row mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getBigDecimal("target_amount"),
                rs.getString("currency"),
                rs.getObject("target_date", LocalDate.class),
                rs.getBigDecimal("monthly_target"),
                rs.getObject("account_id", UUID.class),
                rs.getString("account_name"),
                rs.getString("notes"),
                rs.getString("status"),
                rs.getObject("achieved_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private record Row(
            UUID id, String name, BigDecimal targetAmount, String currency,
            LocalDate targetDate, BigDecimal monthlyTarget, UUID accountId,
            String accountName, String notes, String status,
            OffsetDateTime achievedAt, OffsetDateTime createdAt) {}

    private record Totals(BigDecimal saved, LocalDate firstOn) {}
}
