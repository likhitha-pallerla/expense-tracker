package com.expensetracker.api.profile;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a user's profile and default categories/accounts on first contact.
 *
 * <p>Supabase owns the {@code auth.users} table and newer projects disallow
 * triggers on it, so provisioning is driven from the API instead: every
 * authenticated request that needs a profile calls {@link #ensureProvisioned}.
 * Both steps are idempotent, and the insert uses {@code on conflict do nothing}
 * so two concurrent first requests cannot produce a duplicate or an error.
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final JdbcTemplate jdbc;

    public ProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ProfileView ensureProvisioned(UUID userId, String email) {
        int inserted = jdbc.update("""
                insert into profiles (id, display_name)
                values (?, ?)
                on conflict (id) do nothing
                """, userId, defaultDisplayName(email));

        boolean newlyProvisioned = inserted > 0;

        if (newlyProvisioned) {
            log.info("Provisioning defaults for new user {}", userId);
        }

        // Safe to call every time: the function returns early once the user has
        // categories, which also repairs a profile created before seeding ran.
        // Uses query() rather than update() because a SELECT returns a result set.
        jdbc.query("select seed_user_defaults(?)", rs -> null, userId);

        return load(userId, email, newlyProvisioned);
    }

    private ProfileView load(UUID userId, String email, boolean newlyProvisioned) {
        try {
            return jdbc.queryForObject("""
                    select display_name, base_currency, timezone, locale, onboarded_at
                    from profiles
                    where id = ?
                    """,
                    (rs, rowNum) -> new ProfileView(
                            userId,
                            email,
                            rs.getString("display_name"),
                            rs.getString("base_currency"),
                            rs.getString("timezone"),
                            rs.getString("locale"),
                            rs.getObject("onboarded_at", java.time.OffsetDateTime.class),
                            newlyProvisioned),
                    userId);
        } catch (EmptyResultDataAccessException ex) {
            throw new IllegalStateException("Profile missing immediately after provisioning: " + userId, ex);
        }
    }

    static String defaultDisplayName(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
