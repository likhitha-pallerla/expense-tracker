package com.expensetracker.api.profile;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The user's own calendar.
 *
 * <p>Anything that turns a date into an instant — a statement row, a budget
 * window — has to do it in the user's timezone, or a late-night purchase lands
 * on the wrong day and is counted against the wrong period.
 */
@Service
public class UserSettings {

    /** India-first product; also the fallback when a profile is incomplete. */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;

    public UserSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ZoneId zoneOf(UUID userId) {
        String timezone = jdbc.query(
                "select timezone from profiles where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);

        if (timezone == null || timezone.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ex) {
            // A bad stored value must not break every date calculation.
            return DEFAULT_ZONE;
        }
    }

    /** Today as the user would write it, not as the server's clock sees it. */
    public LocalDate today(UUID userId) {
        return LocalDate.now(zoneOf(userId));
    }

    public String baseCurrency(UUID userId) {
        String currency = jdbc.query(
                "select base_currency from profiles where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);
        return currency == null || currency.isBlank() ? "INR" : currency;
    }
}
