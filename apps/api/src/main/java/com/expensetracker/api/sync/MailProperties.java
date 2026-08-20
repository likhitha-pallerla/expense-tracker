package com.expensetracker.api.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the mail APIs live.
 *
 * <p>Separate from the OAuth settings because they answer different questions:
 * those are about proving who is asking, these are about where to ask. They
 * also change for different reasons — a deployment might have credentials for
 * only one provider while both API endpoints stay exactly as shipped.
 *
 * <p>Both are configurable purely so the sync can be pointed at a stub and
 * driven end to end without a real mailbox. In production nobody should ever
 * set them.
 */
@ConfigurationProperties("app.mail")
public record MailProperties(String gmailBase, String graphBase) {

    public MailProperties {
        gmailBase = orDefault(gmailBase, "https://gmail.googleapis.com");
        graphBase = orDefault(graphBase, "https://graph.microsoft.com");
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.replaceAll("/+$", "");
    }
}
