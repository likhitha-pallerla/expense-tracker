package com.expensetracker.api.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.expensetracker.api.connections.MailProvider;
import com.expensetracker.api.connections.OAuthClient;
import com.expensetracker.api.connections.OAuthProperties;
import com.expensetracker.api.connections.TokenCipher;

/**
 * Produces an access token that will still be valid for the length of a sync.
 *
 * <p>Access tokens last about an hour and syncs are triggered by hand, so by
 * the time anyone presses anything the stored token is usually dead. Refreshing
 * is therefore the normal path, not the exception, and it has to be invisible:
 * a user should never be asked to reconnect a mailbox that is working perfectly
 * well.
 *
 * <h2>Why the expiry check has slack in it</h2>
 *
 * <p>A token is treated as expired a minute early. Checking for "expired right
 * now" means a token with four seconds left passes the check and dies mid-run,
 * halfway through a page of messages, producing a failure that no amount of
 * retrying reproduces and no log explains.
 *
 * <h2>Why a failed refresh is written down</h2>
 *
 * <p>When a refresh token stops working — the user revoked access in their
 * Google account, or changed their password, or the grant simply aged out —
 * nothing will ever fix it except the user reconnecting. Marking the connection
 * {@code needs_reauth} is what turns an invisible dead mailbox into something
 * the UI can point at and say so.
 */
@Component
public class AccessTokens {

    private static final Logger log = LoggerFactory.getLogger(AccessTokens.class);

    /** How close to expiry counts as expired. */
    private static final Duration SLACK = Duration.ofMinutes(1);

    private final JdbcTemplate jdbc;
    private final OAuthProperties properties;
    private final OAuthClient oauth;
    private final TokenCipher cipher;

    public AccessTokens(JdbcTemplate jdbc, OAuthProperties properties,
                        OAuthClient oauth, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.oauth = oauth;
        this.cipher = cipher;
    }

    /**
     * @param connectionId the mailbox to get a token for
     * @param userId       whose it is. Passed separately rather than looked up,
     *                     because it is the additional authenticated data the
     *                     stored token was encrypted with: getting it wrong
     *                     must fail to decrypt, not decrypt someone else's
     */
    public String forConnection(UUID userId, UUID connectionId,
                                MailProvider provider,
                                String encryptedAccess, Instant accessExpiresAt,
                                String encryptedRefresh) {

        boolean stillGood = encryptedAccess != null
                && accessExpiresAt != null
                && accessExpiresAt.isAfter(Instant.now().plus(SLACK));

        if (stillGood) {
            try {
                return cipher.decrypt(encryptedAccess, userId.toString());
            } catch (RuntimeException e) {
                // The key changed, or the row was tampered with. Refreshing
                // gets a working token without a human being involved, which is
                // better than refusing to sync until someone notices.
                log.warn("Stored access token for connection {} would not decrypt; refreshing", connectionId);
            }
        }

        if (encryptedRefresh == null) {
            markNeedsReauth(connectionId, "No refresh token is stored for this mailbox.");
            throw new MailFetchException("This mailbox needs to be reconnected.");
        }

        String refreshToken;
        try {
            refreshToken = cipher.decrypt(encryptedRefresh, userId.toString());
        } catch (RuntimeException e) {
            markNeedsReauth(connectionId, "Stored credentials could not be read.");
            throw new MailFetchException("This mailbox needs to be reconnected.");
        }

        OAuthClient.Tokens tokens;
        try {
            tokens = oauth.refresh(properties.forProvider(provider), refreshToken);
        } catch (RuntimeException e) {
            markNeedsReauth(connectionId, "The mail provider refused to renew access.");
            throw new MailFetchException("This mailbox needs to be reconnected.", e);
        }

        store(userId, connectionId, tokens);
        return tokens.accessToken();
    }

    /**
     * Saves what the refresh returned.
     *
     * <p>Providers usually do not send a new refresh token on a refresh, and
     * the ones that do expect the old one to be replaced. Writing null over a
     * working refresh token would disconnect the mailbox on the next run, so
     * the existing value is kept unless a replacement actually arrived.
     */
    private void store(UUID userId, UUID connectionId, OAuthClient.Tokens tokens) {
        String encryptedAccess = tokens.accessToken() == null
                ? null
                : cipher.encrypt(tokens.accessToken(), userId.toString());

        String encryptedRefresh = tokens.refreshToken() == null
                ? null
                : cipher.encrypt(tokens.refreshToken(), userId.toString());

        jdbc.update("""
                update source_connections
                set encrypted_access_token = ?,
                    token_expires_at = ?,
                    encrypted_refresh_token = coalesce(?, encrypted_refresh_token),
                    status = case when status = 'needs_reauth' then 'active'::connection_status
                                  else status end,
                    last_error = null
                where id = ?
                """,
                encryptedAccess,
                tokens.expiresAt() == null ? null : java.sql.Timestamp.from(tokens.expiresAt()),
                encryptedRefresh,
                connectionId);
    }

    private void markNeedsReauth(UUID connectionId, String reason) {
        jdbc.update("""
                update source_connections
                set status = 'needs_reauth'::connection_status, last_error = ?
                where id = ?
                """, reason, connectionId);
    }
}
