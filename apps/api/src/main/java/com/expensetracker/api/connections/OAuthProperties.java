package com.expensetracker.api.connections;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about the OAuth handshake that changes between environments.
 *
 * <p>The provider endpoints are configurable rather than hard-coded, which
 * looks like over-engineering until you try to test the flow: with URLs in
 * config the whole exchange can be pointed at a local stub and driven
 * end-to-end, and without it none of this code is reachable by a test at all.
 */
@ConfigurationProperties(prefix = "app.oauth")
public record OAuthProperties(
        /**
         * Public base URL of this API, used to build the redirect URI.
         *
         * <p>Must match the registered redirect exactly, including scheme and
         * port; providers compare it as a string. It is configured rather than
         * read from the incoming request because a request header is attacker
         * controlled, and a redirect URI taken from one is a well-known way to
         * have authorisation codes delivered somewhere else.
         */
        String apiBase,

        /** Where the browser is sent once the handshake finishes. */
        String webBase,

        /** How long an unfinished authorisation stays valid. */
        int stateTtlSeconds,

        Provider gmail,
        Provider outlook) {

    public OAuthProperties {
        apiBase = trimTrailingSlash(apiBase);
        webBase = trimTrailingSlash(webBase);
        stateTtlSeconds = stateTtlSeconds <= 0 ? 600 : stateTtlSeconds;
    }

    public Provider forProvider(MailProvider provider) {
        return switch (provider) {
            case GMAIL -> gmail;
            case OUTLOOK -> outlook;
        };
    }

    public String redirectUri(MailProvider provider) {
        return apiBase + "/api/connections/callback/" + provider.key();
    }

    private static String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    /**
     * @param clientId     absent means the provider is not set up on this
     *                     deployment, which is normal: Gmail and Outlook are
     *                     registered separately and one usually lands first
     * @param userInfoUri  endpoint that names the signed-in mailbox, so the UI
     *                     can show which account is connected
     * @param revokeUri    optional; Microsoft has no equivalent single-token
     *                     revocation endpoint
     */
    public record Provider(
            String clientId,
            String clientSecret,
            String authorizeUri,
            String tokenUri,
            String userInfoUri,
            String revokeUri) {

        public boolean isConfigured() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }
}
