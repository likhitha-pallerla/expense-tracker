package com.expensetracker.api.ratelimit;

import org.springframework.http.HttpMethod;

/**
 * What a request costs the server, which is what its limit should be based on.
 *
 * <p>Cost is not the same as HTTP method, and classifying by method alone gets
 * this wrong in both directions. {@code GET /api/insights/summary} may call a
 * model and take seconds; {@code POST /api/parse/{id}/ignore} sets one column.
 * Someone triaging fifty unread messages would be throttled by a rule that read
 * "POST under /api/parse is expensive" — and that person is doing exactly what
 * the application asked of them.
 */
public enum RequestCost {

    /** Reading records already in the database. */
    READ,

    /** Creating or changing records by hand. */
    WRITE,

    /** Talks to a mail provider, parses a mailbox, or ingests a file. */
    SYNC,

    /** May reach a model, which costs money and takes seconds. */
    AI,

    /** No authenticated user yet, so the limit is per IP address. */
    ANONYMOUS;

    /** Paths where the work is a network round trip or a bulk parse. */
    private static final String[] EXPENSIVE = {
        "/api/sync",
        "/api/parse",
        "/api/imports",
        "/api/sms",
        "/api/connections",
    };

    /**
     * Cheap writes that happen to live under an expensive path. Marking a
     * message as ignored is one UPDATE, done from a list, one click at a time.
     */
    private static final String[] CHEAP_WRITES = {
        "/ignore",
    };

    /**
     * Classifies a request by its path and method.
     *
     * @param method HTTP method
     * @param path   request path, starting with a slash
     */
    public static RequestCost of(String method, String path) {
        if (path == null) {
            return READ;
        }

        // First, because one of these is a GET. The AI's own daily budget caps
        // what a user can spend in a day; this stops a client spending it all
        // in a minute.
        if (path.startsWith("/api/entry/parse") || path.startsWith("/api/insights/summary")) {
            return AI;
        }

        // Every remaining expensive endpoint is a POST or a DELETE, so reading
        // is never charged the expensive rate. The lists those pages show —
        // connections, sync runs, the parse queue — stay as cheap as they are.
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            return READ;
        }

        for (String suffix : CHEAP_WRITES) {
            if (path.endsWith(suffix)) {
                return WRITE;
            }
        }

        for (String prefix : EXPENSIVE) {
            if (path.startsWith(prefix)) {
                return SYNC;
            }
        }

        return WRITE;
    }
}
