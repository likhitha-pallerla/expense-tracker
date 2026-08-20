package com.expensetracker.api.connections;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The mailbox providers that can be connected.
 *
 * <p>Kept separate from the {@code connection_provider} database enum, which
 * also covers CSV imports, manual entry and Android SMS. Those arrive by other
 * routes and have no OAuth handshake; putting them in the same type would mean
 * every switch here had to explain why they do nothing.
 */
public enum MailProvider {

    /**
     * Gmail.
     *
     * <p>{@code gmail.readonly} is a restricted scope: Google requires a
     * security assessment before an app using it can be published to users
     * outside the test list. Read-only is nonetheless the right ask — nothing
     * here ever needs to send, delete or label mail, and requesting less than
     * the minimum is the only part of that review under our control.
     */
    GMAIL("gmail", List.of(
            "openid",
            "email",
            "https://www.googleapis.com/auth/gmail.readonly")),

    /**
     * Outlook, through Microsoft Graph.
     *
     * <p>{@code offline_access} is what makes Microsoft issue a refresh token
     * at all; without it the connection would die within the hour and the user
     * would be asked to sign in again every time a sync ran.
     */
    OUTLOOK("outlook", List.of(
            "openid",
            "email",
            "offline_access",
            "https://graph.microsoft.com/Mail.Read"));

    private final String key;
    private final List<String> scopes;

    MailProvider(String key, List<String> scopes) {
        this.key = key;
        this.scopes = scopes;
    }

    public String key() {
        return key;
    }

    public List<String> scopes() {
        return scopes;
    }

    public String scopeParameter() {
        return String.join(" ", scopes);
    }

    public static Optional<MailProvider> from(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        for (MailProvider provider : values()) {
            if (provider.key.equals(normalised)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /**
     * Pulls the mailbox address out of the provider's account response.
     *
     * <p>The two disagree about where it lives. Google returns {@code email}.
     * Microsoft Graph returns {@code mail}, which is null for accounts with no
     * Exchange mailbox — personal accounts especially — where
     * {@code userPrincipalName} is the address people actually recognise.
     */
    public String addressFrom(Map<String, Object> account) {
        if (account == null) {
            return null;
        }
        return switch (this) {
            case GMAIL -> string(account, "email");
            case OUTLOOK -> {
                String mail = string(account, "mail");
                yield mail != null ? mail : string(account, "userPrincipalName");
            }
        };
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** How the connection is described in the UI before a mailbox is known. */
    public String label() {
        return switch (this) {
            case GMAIL -> "Gmail";
            case OUTLOOK -> "Outlook";
        };
    }
}
