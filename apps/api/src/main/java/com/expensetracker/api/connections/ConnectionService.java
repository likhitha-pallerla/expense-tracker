package com.expensetracker.api.connections;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.connections.OAuthClient.Tokens;
import com.expensetracker.api.connections.OAuthProperties.Provider;

/**
 * Connecting, holding and dropping a mailbox.
 *
 * <h2>Why the browser never carries the user id</h2>
 *
 * The flow starts on an authenticated call, which is where the user is known.
 * That identity is written into {@code oauth_states} and read back when the
 * provider redirects. The callback itself is unauthenticated — it arrives as a
 * plain browser navigation with no Authorization header — so anything it
 * claimed about who it was would be a claim from an attacker.
 *
 * <h2>Why the state is spent</h2>
 *
 * A callback URL is a working credential until the code inside it is used. It
 * sits in browser history and in any log along the way. Marking the state
 * consumed inside the same transaction that reads it means a replay finds it
 * already spent, and a second tab racing the first loses cleanly.
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    private final JdbcTemplate jdbc;
    private final OAuthProperties properties;
    private final OAuthClient oauth;
    private final TokenCipher cipher;

    public ConnectionService(JdbcTemplate jdbc, OAuthProperties properties,
            OAuthClient oauth, TokenCipher cipher) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.oauth = oauth;
        this.cipher = cipher;
    }

    // ---- listing -----------------------------------------------------------

    public List<ConnectionView> list(UUID userId) {
        return jdbc.query("""
                select id, provider::text as provider, external_account, display_name,
                       status::text as status, last_error, last_synced_at, connected_at
                from source_connections
                where user_id = ? and provider in ('gmail', 'outlook') and status <> 'revoked'
                order by created_at
                """, (rs, row) -> toView(rs), userId);
    }

    /**
     * What the connections page shows: every provider we support, connected or
     * not, and whether connecting is even possible on this deployment.
     */
    public List<Map<String, Object>> providers(UUID userId) {
        List<ConnectionView> existing = list(userId);

        List<Map<String, Object>> available = new ArrayList<>();
        for (MailProvider provider : MailProvider.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("provider", provider.key());
            entry.put("label", provider.label());
            entry.put("configured", isReady(provider));
            entry.put("connections", existing.stream()
                    .filter(view -> view.provider().equals(provider.key()))
                    .toList());
            available.add(entry);
        }
        return available;
    }

    /**
     * Phones that have uploaded SMS alerts.
     *
     * <p>Kept apart from {@link #providers} because a phone is not something
     * you connect from this page — it appears because an app signed in and
     * started sending, and the only action available here is to stop it. It is
     * listed at all so that "which devices can read my messages?" has an answer
     * on a screen the user can reach, rather than only on the phone itself.
     */
    public List<ConnectionView> devices(UUID userId) {
        return jdbc.query("""
                select id, provider::text as provider, external_account, display_name,
                       status::text as status, last_error, last_synced_at, connected_at
                from source_connections
                where user_id = ? and provider = 'android_sms' and status <> 'revoked'
                order by created_at
                """, (rs, row) -> toView(rs), userId);
    }

    private boolean isReady(MailProvider provider) {
        Provider config = properties.forProvider(provider);
        return config != null && config.isConfigured() && cipher.isConfigured();
    }

    // ---- starting the handshake -------------------------------------------

    /**
     * Builds the URL to send the user to, and remembers the attempt.
     *
     * @return the provider's authorise URL, complete with state and PKCE
     *         challenge
     */
    @Transactional
    public String startAuthorization(UUID userId, MailProvider provider, String returnPath) {
        Provider config = requireConfigured(provider);

        String state = Pkce.newState();
        String verifier = Pkce.newVerifier();

        jdbc.update("""
                insert into oauth_states (user_id, provider, state, code_verifier, return_path, expires_at)
                values (?, ?::connection_provider, ?, ?, ?, now() + make_interval(secs => ?))
                """,
                userId, provider.key(), state, verifier, safeReturnPath(returnPath),
                properties.stateTtlSeconds());

        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", config.clientId());
        params.put("redirect_uri", properties.redirectUri(provider));
        params.put("response_type", "code");
        params.put("scope", provider.scopeParameter());
        params.put("state", state);
        params.put("code_challenge", Pkce.challengeFor(verifier));
        params.put("code_challenge_method", "S256");

        // Google issues a refresh token only on the first consent unless it is
        // asked again explicitly. Someone who disconnects and reconnects would
        // otherwise get an access token good for an hour and a connection that
        // silently dies, which looks exactly like a bug in the sync.
        if (provider == MailProvider.GMAIL) {
            params.put("access_type", "offline");
            params.put("prompt", "consent");
        }

        StringBuilder url = new StringBuilder(config.authorizeUri());
        url.append(config.authorizeUri().contains("?") ? '&' : '?');
        params.forEach((key, value) -> url
                .append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8))
                .append('&'));
        url.setLength(url.length() - 1);

        return url.toString();
    }

    /**
     * Only a path within our own web app is ever accepted.
     *
     * <p>A caller-supplied return address that reached a redirect unchecked
     * would be an open redirect, and one attached to an OAuth flow is the
     * classic way to have a code delivered to somebody else's site.
     */
    static String safeReturnPath(String returnPath) {
        if (returnPath == null || returnPath.isBlank()) {
            return "/connections";
        }
        if (!returnPath.startsWith("/") || returnPath.startsWith("//")) {
            return "/connections";
        }
        return returnPath;
    }

    // ---- finishing the handshake ------------------------------------------

    /**
     * Handles the provider's redirect and returns where to send the browser.
     *
     * <p>Never throws for a failed authorisation. The caller is a browser
     * following a redirect, and an error page served from the API would strand
     * the user outside the app with no way back; the outcome travels as a query
     * parameter on the return URL instead.
     */
    public String completeAuthorization(String stateValue, String code, String error) {
        PendingState pending = consume(stateValue);
        if (pending == null) {
            // No state, so no idea who this is or where they came from.
            return webUrl("/connections", "error", "expired");
        }

        if (error != null && !error.isBlank()) {
            // access_denied is someone pressing "cancel", which is not a fault.
            log.info("Authorisation for {} ended with '{}'", pending.provider().key(), error);
            return webUrl(pending.returnPath(), "error",
                    "access_denied".equals(error) ? "cancelled" : "refused");
        }

        if (code == null || code.isBlank()) {
            return webUrl(pending.returnPath(), "error", "no_code");
        }

        try {
            store(pending, code);
        } catch (ResponseStatusException e) {
            log.warn("Could not complete {} connection: {}", pending.provider().key(), e.getReason());
            return webUrl(pending.returnPath(), "error", "exchange_failed");
        }

        return webUrl(pending.returnPath(), "connected", pending.provider().key());
    }

    private void store(PendingState pending, String code) {
        MailProvider provider = pending.provider();
        Provider config = requireConfigured(provider);

        Tokens tokens = oauth.exchangeCode(config, code,
                pending.verifier(), properties.redirectUri(provider));

        if (tokens.refreshToken() == null) {
            // Without one, the connection expires within the hour and cannot be
            // renewed. Better to refuse now than to store something that looks
            // connected and stops working over lunch.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The provider did not issue a refresh token.");
        }

        String address = provider.addressFrom(oauth.fetchAccount(config, tokens.accessToken()));
        String owner = pending.userId().toString();

        // Reconnecting the same mailbox updates the existing row rather than
        // adding a second one: two live connections to one inbox would import
        // every message twice and leave the duplicate engine to clean it up.
        jdbc.update("""
                insert into source_connections (
                    user_id, provider, external_account, display_name, status,
                    encrypted_refresh_token, encrypted_access_token, token_expires_at,
                    granted_scopes, connected_at, last_error)
                values (?, ?::connection_provider, ?, ?, 'active'::connection_status,
                        ?, ?, ?, ?, now(), null)
                on conflict (user_id, provider, external_account) do update set
                    status = 'active'::connection_status,
                    display_name = excluded.display_name,
                    encrypted_refresh_token = excluded.encrypted_refresh_token,
                    encrypted_access_token = excluded.encrypted_access_token,
                    token_expires_at = excluded.token_expires_at,
                    granted_scopes = excluded.granted_scopes,
                    connected_at = now(),
                    last_error = null
                """,
                pending.userId(), provider.key(), address, provider.label(),
                cipher.encrypt(tokens.refreshToken(), owner),
                cipher.encrypt(tokens.accessToken(), owner),
                tokens.expiresAt() == null ? null : Timestamp.from(tokens.expiresAt()),
                tokens.scope());

        log.info("Connected a {} mailbox for user {}", provider.key(), pending.userId());
    }

    /**
     * Reads a state and spends it in one statement.
     *
     * <p>The {@code consumed_at is null} check inside the update is what makes
     * this safe under a double submission: the second caller updates no rows
     * and gets nothing back, rather than both callers reading an unspent row
     * and both proceeding.
     */
    private PendingState consume(String stateValue) {
        if (stateValue == null || stateValue.isBlank()) {
            return null;
        }

        List<PendingState> found = jdbc.query("""
                update oauth_states set consumed_at = now()
                where state = ? and consumed_at is null and expires_at > now()
                returning user_id, provider::text as provider, code_verifier, return_path
                """, (rs, row) -> new PendingState(
                        rs.getObject("user_id", UUID.class),
                        MailProvider.from(rs.getString("provider")).orElseThrow(),
                        rs.getString("code_verifier"),
                        rs.getString("return_path")),
                stateValue);

        return found.isEmpty() ? null : found.get(0);
    }

    // ---- disconnecting -----------------------------------------------------

    /**
     * Drops a connection.
     *
     * <p>The row is deleted rather than flagged. Everywhere else in this system
     * deletes are soft, because financial history has to stay recoverable — but
     * a stored credential is the opposite case. "Disconnect" has to mean the
     * token is gone, not hidden behind a flag that some future query forgets to
     * filter on. Imported transactions are unaffected; they belong to the user,
     * not to the mailbox they arrived through.
     *
     * <p>Works for a phone as well as a mailbox. A phone has no token and no
     * provider to notify, so deleting the row <em>is</em> the revocation — but
     * it must still be possible, or the one device that can read a person's
     * messages would be the one thing they could not switch off.
     */
    @Transactional
    public void disconnect(UUID userId, UUID connectionId) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    select provider::text as provider, encrypted_refresh_token
                    from source_connections
                    where id = ? and user_id = ?
                    """, connectionId, userId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such connection.");
        }

        String providerKey = (String) row.get("provider");
        String sealed = (String) row.get("encrypted_refresh_token");

        if (shouldRevokeUpstream(providerKey, sealed, cipher.isConfigured())) {
            MailProvider provider = MailProvider.from(providerKey).orElseThrow();
            try {
                oauth.revoke(properties.forProvider(provider),
                        cipher.decrypt(sealed, userId.toString()));
            } catch (IllegalStateException e) {
                // An unreadable token is one we cannot revoke and one nobody
                // else can use either. Deleting it is still the right move.
                log.warn("Stored token for connection {} could not be decrypted on disconnect",
                        connectionId);
            }
        }

        jdbc.update("delete from source_connections where id = ? and user_id = ?",
                connectionId, userId);
    }

    /**
     * Whether disconnecting should also tell the provider.
     *
     * <p>Extracted because the answer used to be assumed rather than asked: the
     * code took a mailbox for granted and rejected everything else as "not a
     * mailbox", which quietly made an Android phone impossible to disconnect.
     * All three reasons to skip the call are genuine and none of them should
     * stop the row being deleted — there is no upstream to notify, no token to
     * hand back, or no key to read it with.
     */
    static boolean shouldRevokeUpstream(String providerKey, String sealedToken, boolean cipherReady) {
        return MailProvider.from(providerKey).isPresent()
                && sealedToken != null
                && cipherReady;
    }

    // ---- helpers -----------------------------------------------------------

    private Provider requireConfigured(MailProvider provider) {
        if (!cipher.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Mailbox connections are not available: this server has no encryption key.");
        }
        Provider config = properties.forProvider(provider);
        if (config == null || !config.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    provider.label() + " is not set up on this server yet.");
        }
        return config;
    }

    private String webUrl(String path, String key, String value) {
        return properties.webBase() + (path == null ? "/connections" : path)
                + "?" + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private ConnectionView toView(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        boolean needsReauth = "needs_reauth".equals(status) || "error".equals(status);

        return new ConnectionView(
                rs.getObject("id", UUID.class),
                rs.getString("provider"),
                rs.getString("display_name"),
                rs.getString("external_account"),
                status,
                describe(status),
                instant(rs.getTimestamp("connected_at")),
                instant(rs.getTimestamp("last_synced_at")),
                rs.getString("last_error"),
                needsReauth);
    }

    private static String describe(String status) {
        return switch (status) {
            case "active" -> "Connected";
            case "needs_reauth" -> "Sign in again to keep importing";
            case "paused" -> "Paused";
            case "error" -> "Something went wrong on the last sync";
            case "revoked" -> "Disconnected";
            default -> status;
        };
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record PendingState(UUID userId, MailProvider provider, String verifier, String returnPath) {
    }
}
