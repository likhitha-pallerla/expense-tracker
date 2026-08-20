package com.expensetracker.api.merchants;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves free-text merchant strings to stable merchant rows.
 *
 * <p>Bank feeds spell the same shop a dozen ways ("UPI-SWIGGY LTD",
 * "POS SWIGGY", "SWIGGY*ORDER 1234"). Grouping by raw text would scatter one
 * merchant across many rows and make category rules useless, so every string is
 * normalised to a canonical name first and the raw form is kept as an alias.
 */
@Service
public class MerchantService {

    private final JdbcTemplate jdbc;

    public MerchantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the merchant id for {@code rawName}, creating it if new.
     *
     * <p>Returns empty when the text normalises to nothing — "UPI REF 123"
     * names a payment rail, not a merchant, and storing it would pollute
     * reports with meaningless groups.
     */
    @Transactional
    public Optional<UUID> resolve(UUID userId, String rawName) {
        String normalized = MerchantNormalizer.normalize(rawName);
        if (normalized == null) {
            return Optional.empty();
        }

        Optional<UUID> viaAlias = queryId("""
                select merchant_id from merchant_aliases
                where user_id = ? and normalized_text = ?
                """, userId, normalized);
        if (viaAlias.isPresent()) {
            return viaAlias;
        }

        // ON CONFLICT rather than check-then-insert: two imports of the same
        // statement can race, and the unique index is the only real guard.
        UUID merchantId = jdbc.queryForObject("""
                insert into merchants (user_id, name, normalized_name)
                values (?, ?, ?)
                on conflict (user_id, normalized_name)
                do update set name = merchants.name
                returning id
                """, UUID.class, userId, normalized, normalized);

        jdbc.update("""
                insert into merchant_aliases (user_id, merchant_id, raw_text, normalized_text)
                values (?, ?, ?, ?)
                on conflict (user_id, normalized_text) do nothing
                """, userId, merchantId, rawName.trim(), normalized);

        return Optional.ofNullable(merchantId);
    }

    /** The merchant's remembered category, used to auto-fill new transactions. */
    public Optional<UUID> defaultCategory(UUID userId, UUID merchantId) {
        return queryId("""
                select default_category_id from merchants
                where user_id = ? and id = ? and default_category_id is not null
                """, userId, merchantId);
    }

    /**
     * Remembers the category a user picked for a merchant, so the next
     * transaction from the same shop is categorised without asking again.
     */
    @Transactional
    public void rememberCategory(UUID userId, UUID merchantId, UUID categoryId) {
        if (merchantId == null || categoryId == null) {
            return;
        }
        jdbc.update("""
                update merchants set default_category_id = ?
                where user_id = ? and id = ? and default_category_id is null
                """, categoryId, userId, merchantId);
    }

    private Optional<UUID> queryId(String sql, Object... args) {
        return jdbc.query(sql, rs -> rs.next()
                ? Optional.ofNullable(rs.getObject(1, UUID.class))
                : Optional.<UUID>empty(), args);
    }
}
