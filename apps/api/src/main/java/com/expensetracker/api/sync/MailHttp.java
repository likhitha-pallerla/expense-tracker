package com.expensetracker.api.sync;

import java.util.Map;

/**
 * The one thing a fetcher needs from the network: fetch a URL as JSON.
 *
 * <p>This exists so the interesting parts of syncing — pagination, cursor
 * expiry, decoding a MIME tree, deciding when to stop — can be tested against
 * exact provider responses without a socket. Those are the parts that break,
 * and they break on responses that are awkward to produce on demand from a real
 * provider: a history id Google has forgotten, a delta link Microsoft has
 * expired, a message whose body is nested three parts deep.
 */
@FunctionalInterface
public interface MailHttp {

    /**
     * @throws MailCursorLostException if the provider says the resume point is
     *                                 no longer valid ({@code 404} from Gmail's
     *                                 history feed, {@code 410} from Graph's
     *                                 delta feed)
     * @throws MailFetchException      for anything else that went wrong
     */
    Map<String, Object> get(String url, String accessToken);
}
