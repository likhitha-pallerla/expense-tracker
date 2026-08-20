package com.expensetracker.api.notifications;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.budgets.BudgetService;
import com.expensetracker.api.cards.CardService;
import com.expensetracker.api.dedup.DedupService;
import com.expensetracker.api.profile.UserSettings;
import com.expensetracker.api.recurring.RecurringService;

/**
 * Merges live alerts with what the user has already done about them.
 *
 * <p>The alerts themselves are never stored. They are rebuilt from budgets,
 * cards, subscriptions and the review queue every time this is called, so an
 * alert cannot outlive its cause: pay the card and it goes, delete the
 * transaction that blew the budget and it goes. Only the user's decisions are
 * written down, because those are the one thing that cannot be derived.
 *
 * <p>Rows whose alert no longer exists are left alone rather than swept up. A
 * dismissal has to survive the alert disappearing and coming back — that is
 * precisely the case it exists for — and a read receipt for something long gone
 * costs a row and harms nothing.
 */
@Service
public class NotificationService {

    private final JdbcTemplate jdbc;
    private final UserSettings settings;
    private final BudgetService budgets;
    private final CardService cards;
    private final RecurringService recurring;
    private final DedupService dedup;

    public NotificationService(JdbcTemplate jdbc, UserSettings settings, BudgetService budgets,
            CardService cards, RecurringService recurring, DedupService dedup) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.budgets = budgets;
        this.cards = cards;
        this.recurring = recurring;
        this.dedup = dedup;
    }

    public List<NotificationView> list(UUID userId, boolean includeDismissed) {
        Map<String, Decision> decisions = decisions(userId);

        List<NotificationView> views = new ArrayList<>();
        for (Alert alert : alerts(userId)) {
            Decision decision = decisions.get(alert.key());
            boolean dismissed = decision != null && decision.dismissedAt() != null;
            if (dismissed && !includeDismissed) {
                continue;
            }
            views.add(new NotificationView(
                    alert.key(), alert.type().key(), alert.severity(),
                    alert.title(), alert.body(), alert.href(), alert.occurredOn(),
                    decision != null && decision.readAt() != null, dismissed,
                    decision == null ? null : decision.readAt(),
                    decision == null ? null : decision.dismissedAt()));
        }
        return views;
    }

    /** For the badge in the nav: what is still waiting to be looked at. */
    public long unreadCount(UUID userId) {
        return list(userId, false).stream().filter(view -> !view.read()).count();
    }

    @Transactional
    public NotificationView markRead(UUID userId, String key) {
        Alert alert = require(userId, key);
        record(userId, alert, true, false);
        return reload(userId, key);
    }

    @Transactional
    public int markAllRead(UUID userId) {
        int marked = 0;
        Map<String, Decision> decisions = decisions(userId);
        for (Alert alert : alerts(userId)) {
            Decision decision = decisions.get(alert.key());
            if (decision != null && decision.readAt() != null) {
                continue;
            }
            record(userId, alert, true, false);
            marked++;
        }
        return marked;
    }

    @Transactional
    public NotificationView dismiss(UUID userId, String key) {
        Alert alert = require(userId, key);
        record(userId, alert, true, true);
        return reload(userId, key);
    }

    /**
     * Undoes a dismissal.
     *
     * <p>Leaves the read receipt in place: the user has seen this, they have
     * only changed their mind about wanting it hidden.
     */
    @Transactional
    public NotificationView restore(UUID userId, String key) {
        require(userId, key);
        jdbc.update("update notifications set dismissed_at = null where user_id = ? and alert_key = ?",
                userId, key);
        return reload(userId, key);
    }

    // ---- internals ---------------------------------------------------------

    private List<Alert> alerts(UUID userId) {
        LocalDate today = settings.today(userId);

        return AlertBuilder.build(new AlertBuilder.Inputs(
                budgets.list(userId, false),
                cards.list(userId, false),
                recurring.list(userId, false),
                dedup.pendingCount(userId),
                newestDuplicate(userId)), today);
    }

    private UUID newestDuplicate(UUID userId) {
        return jdbc.query("""
                select id from duplicate_candidates
                where user_id = ? and status = 'pending'
                order by created_at desc, id desc
                limit 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, userId);
    }

    private Map<String, Decision> decisions(UUID userId) {
        Map<String, Decision> decisions = new HashMap<>();
        jdbc.query("""
                select alert_key, read_at, dismissed_at from notifications
                where user_id = ? and alert_key is not null
                """, rs -> {
            decisions.put(rs.getString("alert_key"), new Decision(
                    instant(rs.getTimestamp("read_at")),
                    instant(rs.getTimestamp("dismissed_at"))));
        }, userId);
        return decisions;
    }

    /**
     * Upserts the decision.
     *
     * <p>{@code read_at} is only ever set once — coalescing keeps the moment the
     * user first saw it, which is the only reading of it that means anything.
     * The title is stored as it stood at that moment: nothing displays it, but
     * it is the first thing worth knowing when a dismissal turns out to be a
     * mistake.
     */
    private void record(UUID userId, Alert alert, boolean read, boolean dismissed) {
        jdbc.update("""
                insert into notifications (user_id, type, title, body, alert_key, read_at, dismissed_at)
                values (?, ?, ?, ?, ?, case when ? then now() end, case when ? then now() end)
                on conflict (user_id, alert_key) where alert_key is not null
                do update set
                    type = excluded.type,
                    title = excluded.title,
                    body = excluded.body,
                    read_at = coalesce(notifications.read_at, excluded.read_at),
                    dismissed_at = coalesce(excluded.dismissed_at, notifications.dismissed_at)
                """,
                userId, alert.type().key(), alert.title(), alert.body(), alert.key(),
                read, dismissed);
    }

    private Alert require(UUID userId, String key) {
        return alerts(userId).stream()
                .filter(alert -> alert.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That notification is no longer current."));
    }

    private NotificationView reload(UUID userId, String key) {
        return list(userId, true).stream()
                .filter(view -> view.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That notification is no longer current."));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record Decision(Instant readAt, Instant dismissedAt) {
    }
}
