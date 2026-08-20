package com.expensetracker.api.sync;

import java.time.Instant;

/**
 * One message as it arrived from a mail provider, before anyone has tried to
 * understand it.
 *
 * <p>Nothing here is interpreted. Gmail and Microsoft Graph disagree about
 * almost every detail of shape and naming, and the point of this record is that
 * the disagreement stops at the fetcher: everything downstream sees one thing.
 *
 * @param providerMessageId the provider's own id, kept so the same message is
 *                          never stored twice even if it is fetched twice
 * @param body              plain text where the provider offered it. HTML is
 *                          reduced to text at the edge rather than stored as
 *                          markup, because every later step wants words
 */
public record MailMessage(
        String providerMessageId,
        String sender,
        String subject,
        String snippet,
        String body,
        Instant receivedAt) {

    /**
     * Whether this is worth storing at all.
     *
     * <p>A message with no body is not a payment alert; it is a fetch that went
     * wrong, or a calendar invitation, or a message whose content lives
     * somewhere we did not look. Storing it would put a permanently unparseable
     * row in the queue that no parser can ever resolve.
     */
    public boolean hasContent() {
        return body != null && !body.isBlank();
    }
}
