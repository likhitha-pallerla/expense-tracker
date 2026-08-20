package com.expensetracker.api.connections;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.connections.OAuthProperties.Provider;

/**
 * The half of the handshake that talks to Google and Microsoft.
 *
 * <p>Isolated from {@link ConnectionService} so that the rules — who owns a
 * connection, when a state is spent, what happens on a second callback — can be
 * read without wading through HTTP, and so the network calls can be pointed at
 * a stub in tests.
 */
@Component
public class OAuthClient {

    private static final Logger log = LoggerFactory.getLogger(OAuthClient.class);

    /**
     * Tokens expire on the provider's clock, not ours, and the two are never
     * exactly aligned. A minute of headroom means a token is never presented
     * in the second it becomes invalid.
     */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private final RestClient http;

    public OAuthClient(RestClient.Builder builder) {
        this.http = builder.build();
    }

    /**
     * Exchanges the authorisation code for tokens.
     *
     * @param redirectUri must be byte-identical to the one sent to the
     *                    authorise endpoint; providers re-check it here, and a
     *                    trailing slash is enough to fail
     */
    public Tokens exchangeCode(Provider config, String code, String verifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());
        form.add("code_verifier", verifier);

        return post(config.tokenUri(), form, "exchange the authorisation code");
    }

    public Tokens refresh(Provider config, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());

        return post(config.tokenUri(), form, "refresh the access token");
    }

    /** Asks the provider which mailbox the user just authorised. */
    public Map<String, Object> fetchAccount(Provider config, String accessToken) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> account = http.get()
                    .uri(config.userInfoUri())
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            return account == null ? Map.of() : account;
        } catch (RestClientException e) {
            // Not fatal: the connection works, we just cannot label it. Failing
            // the whole handshake over a display name would be worse than
            // showing "Gmail" without an address.
            log.warn("Could not read the account address from {}: {}",
                    config.userInfoUri(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * Best-effort revocation on disconnect.
     *
     * <p>Failure is logged and swallowed. The user asked to disconnect; the
     * token is deleted either way, and refusing to remove the row because a
     * remote endpoint was unreachable would leave them staring at a connection
     * they have already told us to drop.
     */
    public void revoke(Provider config, String token) {
        if (config.revokeUri() == null || config.revokeUri().isBlank() || token == null) {
            return;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        form.add("client_id", config.clientId());
        form.add("client_secret", config.clientSecret());

        try {
            http.post()
                    .uri(config.revokeUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Revoking the token with the provider failed: {}", e.getMessage());
        }
    }

    private Tokens post(String uri, MultiValueMap<String, String> form, String what) {
        Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = http.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            body = response;
        } catch (RestClientException e) {
            // The exception message can contain the response body, which for a
            // token endpoint may hold a token. It is logged, never returned.
            log.warn("Token endpoint {} failed: {}", uri, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The mail provider refused to " + what + ".");
        }

        if (body == null || body.get("access_token") == null) {
            String error = body == null ? null : String.valueOf(body.get("error"));
            log.warn("Token endpoint {} returned no access token (error={})", uri, error);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The mail provider did not return an access token.");
        }

        return new Tokens(
                (String) body.get("access_token"),
                (String) body.get("refresh_token"),
                expiryFrom(body.get("expires_in")),
                (String) body.get("scope"));
    }

    private static Instant expiryFrom(Object expiresIn) {
        if (expiresIn == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(String.valueOf(expiresIn).trim());
            return Instant.now().plusSeconds(seconds).minus(EXPIRY_MARGIN);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param refreshToken null on a refresh, and on any provider that does not
     *                     rotate them; the stored one must be kept in that case
     *                     rather than overwritten with nothing
     */
    public record Tokens(String accessToken, String refreshToken, Instant expiresAt, String scope) {
    }
}
