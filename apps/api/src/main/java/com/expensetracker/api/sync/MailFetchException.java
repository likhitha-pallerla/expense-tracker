package com.expensetracker.api.sync;

/**
 * A mail fetch failed for a reason we cannot work around.
 *
 * <p>Kept separate from {@link MailCursorLostException} because the two demand
 * opposite responses: a lost cursor means carry on differently, this means stop
 * and tell the user. Collapsing them would turn a revoked token into an endless
 * silent re-scan of the whole mailbox.
 */
public class MailFetchException extends RuntimeException {

    public MailFetchException(String message) {
        super(message);
    }

    public MailFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
