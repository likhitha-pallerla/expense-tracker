package com.expensetracker.api.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The spending cap.
 *
 * <p>A personal API key with no ceiling is one stuck retry loop away from a
 * bill the owner never agreed to, and the failure mode is silent: nothing
 * breaks, the number just grows. So the count lives in Postgres rather than
 * memory. A free Render instance sleeps and restarts several times a day, and
 * an in-memory counter would reset each time — handing a runaway loop a fresh
 * allowance at exactly the wrong moment.
 *
 * <p><strong>Counted before the call, not after.</strong> A call that times out
 * still cost the provider money and still consumed quota, so counting on
 * success would leave the most expensive failure mode — slow requests that
 * eventually fail — completely unmetered.
 */
@Component
public class AiBudget {

    private final JdbcTemplate jdbc;

    public AiBudget(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes one call from today's allowance if there is one left.
     *
     * <p>Runs in its own transaction. Parsing calls this from inside a
     * per-message transaction, and if that message later rolls back the call
     * must still be counted — the model was asked either way, and a rollback
     * that refunded the quota would let a repeatedly failing message ask
     * forever.
     *
     * <p>The limit lives in the {@code where} clause of the upsert rather than
     * in a {@code case} that clamps the value. That distinction matters: a
     * clamped update still <em>succeeds</em> and still returns the ceiling
     * value, so a caller comparing "count is at or under the budget" would read
     * the clamp as permission and let every subsequent call through — a cap
     * that reports full and stops nothing. With the condition here, the update
     * matches no row once the budget is spent, and returning nothing is
     * unambiguous.
     *
     * <p>One statement, so two concurrent requests cannot both read the same
     * count and both conclude they are under the limit.
     *
     * @return true if the caller may proceed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryConsume(UUID userId, int dailyBudget) {
        if (dailyBudget <= 0) {
            return false;
        }

        List<Integer> granted = jdbc.queryForList("""
                insert into ai_usage (user_id, day, calls)
                values (?, current_date, 1)
                on conflict (user_id, day) do update
                   set calls = ai_usage.calls + 1, updated_at = now()
                 where ai_usage.calls < ?
                returning calls
                """, Integer.class, userId, dailyBudget);

        return !granted.isEmpty();
    }

    /** Best-effort accounting; never gates anything. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTokens(UUID userId, long in, long out) {
        jdbc.update("""
                update ai_usage
                   set tokens_in = tokens_in + ?, tokens_out = tokens_out + ?, updated_at = now()
                 where user_id = ? and day = current_date
                """, in, out, userId);
    }

    /** What is left today, for the settings screen. */
    public int remaining(UUID userId, int dailyBudget) {
        List<Integer> used = jdbc.queryForList(
                "select calls from ai_usage where user_id = ? and day = current_date",
                Integer.class, userId);
        return Math.max(0, dailyBudget - (used.isEmpty() ? 0 : used.get(0)));
    }
}
