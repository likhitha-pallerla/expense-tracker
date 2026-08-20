package com.expensetracker.api.sync;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.expensetracker.api.connections.MailProvider;

/**
 * Reads Gmail.
 *
 * <h2>Two different ways to ask "what is new"</h2>
 *
 * <p>Gmail offers a history feed keyed on a {@code historyId}, which is exactly
 * what incremental sync wants: tell it where you were and it lists what has
 * happened since. The catch is that Google only keeps that history for around a
 * week. Any mailbox left alone longer than a holiday comes back to a {@code 404}
 * and there is nothing to be done about it.
 *
 * <p>So there are two paths. With a usable cursor, the history feed. Without
 * one — first sync, or a cursor Google has forgotten — a plain search bounded
 * by date. The second path re-reads messages that may already be stored, which
 * is fine and is the whole reason {@code raw_messages} deduplicates in the
 * schema: correctness does not depend on this class being clever.
 *
 * <h2>Why the id list and the messages are fetched separately</h2>
 *
 * <p>Gmail's list endpoints return ids and nothing else; the content needs one
 * request per message. That is unavoidable, and it is why {@code budget} exists
 * — a first sync of a large mailbox is thousands of round trips, which is a
 * background job on real infrastructure and a killed request on free hosting.
 * Fetching a bounded slice and reporting {@code hasMore} turns one impossible
 * request into several possible ones.
 */
public class GmailFetcher implements MailFetcher {

    private final MailHttp http;
    private final String baseUrl;

    public GmailFetcher(MailHttp http, String baseUrl) {
        this.http = http;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public MailProvider provider() {
        return MailProvider.GMAIL;
    }

    @Override
    public FetchResult fetch(String accessToken, String cursor, Instant backfillFrom, int budget) {
        if (cursor != null && !cursor.isBlank()) {
            try {
                return incremental(accessToken, cursor, budget);
            } catch (MailCursorLostException e) {
                // Expected often enough that it is not worth logging as a
                // problem. Fall through to a dated scan.
                return backfill(accessToken, backfillFrom, budget, true);
            }
        }
        return backfill(accessToken, backfillFrom, budget, false);
    }

    // ---- incremental --------------------------------------------------------

    private FetchResult incremental(String accessToken, String cursor, int budget) {
        Set<String> ids = new LinkedHashSet<>();
        String pageToken = null;
        String latestHistoryId = cursor;
        boolean more = false;

        do {
            String url = baseUrl + "/gmail/v1/users/me/history?historyTypes=messageAdded"
                    + "&startHistoryId=" + enc(cursor)
                    + (pageToken == null ? "" : "&pageToken=" + enc(pageToken));

            Map<String, Object> page = http.get(url, accessToken);

            // The feed's own historyId is the correct next cursor even when the
            // page is empty: it means "nothing happened up to here", and
            // advancing avoids re-asking the same question forever.
            String seen = string(page.get("historyId"));
            if (seen != null) {
                latestHistoryId = seen;
            }

            for (Map<String, Object> record : mapList(page.get("history"))) {
                for (Map<String, Object> added : mapList(record.get("messagesAdded"))) {
                    Map<String, Object> message = map(added.get("message"));
                    String id = message == null ? null : string(message.get("id"));
                    if (id != null) {
                        ids.add(id);
                    }
                }
            }

            pageToken = string(page.get("nextPageToken"));
            if (ids.size() >= budget) {
                more = pageToken != null;
                break;
            }
        } while (pageToken != null);

        List<String> slice = new ArrayList<>(ids);
        if (slice.size() > budget) {
            // More arrived than this run will take. Keep the oldest, so
            // progress is chronological and a repeated run converges instead of
            // ping-ponging between the two ends of the backlog.
            slice = slice.subList(0, budget);
            more = true;
        }

        List<MailMessage> messages = hydrate(accessToken, slice);
        return new FetchResult(messages, latestHistoryId, more, false);
    }

    // ---- backfill -----------------------------------------------------------

    private FetchResult backfill(String accessToken, Instant from, int budget, boolean afterReset) {
        Instant since = from == null
                ? Instant.now().minusSeconds(MailQuery.DEFAULT_BACKFILL_DAYS * 86400L)
                : from;

        String url = baseUrl + "/gmail/v1/users/me/messages"
                + "?maxResults=" + Math.min(budget, 100)
                + "&q=" + enc(MailQuery.gmailQuery(since));

        Map<String, Object> page = http.get(url, accessToken);

        List<String> ids = new ArrayList<>();
        for (Map<String, Object> entry : mapList(page.get("messages"))) {
            String id = string(entry.get("id"));
            if (id != null && ids.size() < budget) {
                ids.add(id);
            }
        }

        boolean more = string(page.get("nextPageToken")) != null;
        List<MailMessage> messages = hydrate(accessToken, ids);

        // The cursor has to come from the profile, not from the search: a
        // search says nothing about where the history feed now stands, and
        // guessing would mean either re-reading everything next time or, far
        // worse, skipping whatever arrived while this run was working.
        return new FetchResult(messages, currentHistoryId(accessToken), more, afterReset);
    }

    private String currentHistoryId(String accessToken) {
        Map<String, Object> profile = http.get(baseUrl + "/gmail/v1/users/me/profile", accessToken);
        return string(profile.get("historyId"));
    }

    // ---- one message --------------------------------------------------------

    private List<MailMessage> hydrate(String accessToken, List<String> ids) {
        List<MailMessage> messages = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> raw = http.get(
                    baseUrl + "/gmail/v1/users/me/messages/" + enc(id) + "?format=full",
                    accessToken);
            MailMessage message = toMessage(raw);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    /**
     * Turns Gmail's message shape into ours.
     *
     * <p>Package-private, and written against the raw map rather than the HTTP
     * call, so the awkward cases can be tested directly: a body nested inside
     * {@code multipart/alternative}, HTML with no plain-text sibling, headers in
     * an order nobody promised.
     */
    static MailMessage toMessage(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        String id = string(raw.get("id"));
        if (id == null) {
            return null;
        }

        Map<String, Object> payload = map(raw.get("payload"));
        String subject = header(payload, "Subject");
        String from = header(payload, "From");
        String body = extractBody(payload);

        Instant receivedAt = null;
        Object internal = raw.get("internalDate");
        if (internal != null) {
            try {
                receivedAt = Instant.ofEpochMilli(Long.parseLong(internal.toString()));
            } catch (NumberFormatException ignored) {
                // A message we cannot date is still worth storing; the parser
                // can usually recover the date from the text, and refusing the
                // whole message over a malformed field would lose real money.
                receivedAt = null;
            }
        }

        return new MailMessage(id, from, subject, string(raw.get("snippet")), body, receivedAt);
    }

    /**
     * Finds the readable text in a MIME tree.
     *
     * <p>Depth-first, preferring {@code text/plain} anywhere in the tree over
     * {@code text/html} anywhere in it — not the first match encountered. Banks
     * routinely send {@code multipart/alternative} with a rich HTML part first
     * and a plain part second, and taking the first would mean stripping tags
     * out of a template when a clean version was sitting right beside it.
     */
    static String extractBody(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        String plain = findPart(payload, "text/plain");
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        String html = findPart(payload, "text/html");
        if (html != null && !html.isBlank()) {
            return Html.toText(html);
        }
        // Single-part messages carry their content on the payload itself and
        // may declare any text/* type.
        String direct = decode(map(payload.get("body")));
        if (direct != null && !direct.isBlank()) {
            String mime = string(payload.get("mimeType"));
            return mime != null && mime.toLowerCase(Locale.ROOT).contains("html")
                    ? Html.toText(direct)
                    : direct;
        }
        return null;
    }

    private static String findPart(Map<String, Object> node, String mimeType) {
        if (node == null) {
            return null;
        }
        String mime = string(node.get("mimeType"));
        if (mimeType.equalsIgnoreCase(mime)) {
            String decoded = decode(map(node.get("body")));
            if (decoded != null && !decoded.isBlank()) {
                return decoded;
            }
        }
        for (Map<String, Object> part : mapList(node.get("parts"))) {
            String found = findPart(part, mimeType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String decode(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        String data = string(body.get("data"));
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            // Gmail uses base64url. Padding is often absent, which the URL
            // decoder tolerates but the MIME decoder does not.
            return new String(Base64.getUrlDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String header(Map<String, Object> payload, String name) {
        if (payload == null) {
            return null;
        }
        for (Map<String, Object> entry : mapList(payload.get("headers"))) {
            if (name.equalsIgnoreCase(string(entry.get("name")))) {
                return string(entry.get("value"));
            }
        }
        return null;
    }

    // ---- json helpers -------------------------------------------------------

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

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
