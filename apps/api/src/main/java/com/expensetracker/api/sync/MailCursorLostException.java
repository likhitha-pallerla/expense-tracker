package com.expensetracker.api.sync;

/**
 * The provider has forgotten where we were.
 *
 * <p>This is routine, not exceptional. Gmail keeps history for about a week;
 * Microsoft expires delta links too. Any mailbox left alone over a holiday will
 * come back to this. It is an exception only because it has to interrupt
 * whatever loop was mid-flight — the handler is a plain fallback to scanning by
 * date, not an error path.
 */
public class MailCursorLostException extends RuntimeException {

    public MailCursorLostException(String message) {
        super(message);
    }
}
