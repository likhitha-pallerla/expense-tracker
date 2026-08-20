package com.expensetracker.api.sync;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.expensetracker.api.connections.MailProvider;

/**
 * Reads Outlook, through Microsoft Graph.
 *
 * <h2>Delta links, not history ids</h2>
 *
 * <p>Graph does incremental sync with an opaque URL. Ask the delta endpoint for
 * a folder, page through with {@code @odata.nextLink}, and the last page hands
 * back an {@code @odata.deltaLink} which <em>is</em> the next run's request.
 * That is genuinely nicer than Gmail's scheme: the cursor is a whole request,
 * so there is nothing to reconstruct and nothing to get wrong.
 *
 * <p>The cost is that delta accepts almost no query options. There is no
 * server-side keyword search, so where Gmail narrows a mailbox to a handful of
 * results before sending anything, Graph sends everything in the folder and the
 * filtering happens here. Both providers are then judged by the same {@link
 * MailQuery}, so a user with both mailboxes gets consistent results rather than
 * whatever each provider's search happened to think.
 *
 * <p>Only the inbox is read. Sent mail, drafts and archives cannot contain an
 * alert the bank sent, and reading fewer folders is both faster and a smaller
 * promise to have to keep.
 */
public class GraphFetcher implements MailFetcher {

    /** Everything needed to recognise and store an alert, and nothing else. */
    private static final String SELECT =
            "id,subject,bodyPreview,receivedDateTime,from,body";

    private final MailHttp http;
    private final String baseUrl;

    public GraphFetcher(MailHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public MailProvider provider() {
        return MailProvider.OUTLOOK;
    }

    @Override
    public FetchResult fetch(String accessToken, String cursor, Instant backfillFrom, int budget) {
        boolean reset = false;
        String url = cursor;

        if (url == null || url.isBlank()) {
            url = firstRequest(backfillFrom, budget);
        }

        List<MailMessage> messages = new ArrayList<>();
        String deltaLink = null;
        boolean more = false;

        while (url != null) {
            Map<String, Object> page;
            try {
                page = http.get(url, accessToken);
            } catch (MailCursorLostException e) {
                if (reset) {
                    // The fallback itself was rejected. Retrying would loop.
                    throw new MailFetchException("Graph rejected a fresh delta request", e);
                }
                // Microsoft expired the link. Start the folder again; the
                // database will drop everything we already have.
                reset = true;
                messages.clear();
                url = firstRequest(backfillFrom, budget);
                continue;
            }

            for (Map<String, Object> item : mapList(page.get("value"))) {
                // Delta feeds report deletions too. A message the user deleted
                // is not an instruction to delete a transaction that was
                // already extracted from it, so these are simply ignored.
                if (item.containsKey("@removed")) {
                    continue;
                }
                MailMessage message = toMessage(item);
                if (message != null) {
                    messages.add(message);
                }
            }

            deltaLink = string(page.get("@odata.deltaLink"));
            String next = string(page.get("@odata.nextLink"));

            if (deltaLink != null) {
                break;
            }
            if (messages.size() >= budget) {
                // Out of budget mid-folder. The next link is the honest resume
                // point — storing it as the cursor means the following run
                // continues the same pass rather than restarting it.
                deltaLink = next;
                more = next != null;
                break;
            }
            url = next;
        }

        if (deltaLink == null) {
            throw new MailFetchException("Graph returned neither a delta link nor a next link");
        }
        return new FetchResult(messages, deltaLink, more, reset);
    }

    private String firstRequest(Instant backfillFrom, int budget) {
        Instant since = backfillFrom == null
                ? Instant.now().minusSeconds(MailQuery.DEFAULT_BACKFILL_DAYS * 86400L)
                : backfillFrom;

        // receivedDateTime is the one field delta will filter on, and it is the
        // one that matters: without it, linking a ten-year-old mailbox would
        // download ten years of mail to throw almost all of it away.
        return baseUrl + "/v1.0/me/mailFolders/inbox/messages/delta"
                + "?$select=" + SELECT
                + "&$top=" + Math.min(budget, 50)
                + "&$filter=receivedDateTime%20ge%20" + since.toString();
    }

    /**
     * Turns a Graph message into ours.
     *
     * <p>Package-private so the shapes that vary can be tested directly: a
     * message from a system address with no {@code from} at all, an HTML body
     * that Graph has labelled as text, a date in a format that changed.
     */
    static MailMessage toMessage(Map<String, Object> item) {
        if (item == null) {
            return null;
        }
        String id = string(item.get("id"));
        if (id == null) {
            return null;
        }

        Map<String, Object> body = map(item.get("body"));
        String content = body == null ? null : string(body.get("content"));
        String contentType = body == null ? null : string(body.get("contentType"));

        // Trust the declared type, but check anyway: Graph labels plenty of
        // HTML as "text", and storing raw markup would poison the body hash and
        // hand the parser a template to read.
        if (content != null
                && ("html".equalsIgnoreCase(contentType) || Html.looksLikeHtml(content))) {
            content = Html.toText(content);
        }

        Map<String, Object> from = map(item.get("from"));
        Map<String, Object> address = from == null ? null : map(from.get("emailAddress"));
        String sender = address == null ? null : string(address.get("address"));

        Instant receivedAt = null;
        String received = string(item.get("receivedDateTime"));
        if (received != null) {
            try {
                receivedAt = Instant.parse(received);
            } catch (DateTimeParseException ignored) {
                receivedAt = null;
            }
        }

        return new MailMessage(id, sender, string(item.get("subject")),
                string(item.get("bodyPreview")), content, receivedAt);
    }

    // ---- json helpers -------------------------------------------------------

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }
}
