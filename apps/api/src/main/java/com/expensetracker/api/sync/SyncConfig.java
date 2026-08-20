package com.expensetracker.api.sync;

import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * Wires the fetchers to real HTTP.
 *
 * <p>The one interesting thing here is the status-code handling. Two codes are
 * not errors in the usual sense and must not be thrown as ordinary failures:
 * Gmail answers {@code 404} when a history id has aged out, and Graph answers
 * {@code 410 Gone} when a delta link has. Both mean the same thing — "start
 * again" — and both are routine on any mailbox left alone for a week. Turning
 * them into {@link MailCursorLostException} here is what lets the fetchers
 * treat them as a fork in the road rather than a wall.
 */
@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class SyncConfig {

    @Bean
    public MailHttp mailHttp(RestClient.Builder builder) {
        RestClient client = builder.build();

        return (url, accessToken) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = client.get()
                        .uri(url)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept", "application/json")
                        .exchange((request, response) -> {
                            HttpStatusCode status = response.getStatusCode();
                            if (status.value() == 404 || status.value() == 410) {
                                throw new MailCursorLostException(
                                        "The mail provider no longer has our resume point (" + status.value() + ")");
                            }
                            if (status.isError()) {
                                throw new MailFetchException(
                                        "The mail provider returned " + status.value());
                            }
                            return response.bodyTo(Map.class);
                        });
                return body == null ? Map.of() : body;
            } catch (MailCursorLostException | MailFetchException e) {
                throw e;
            } catch (RuntimeException e) {
                // Connection refused, DNS, a read timeout. Nothing here is
                // recoverable within one run, but it is temporary, so it must
                // not look like a revoked mailbox.
                throw new MailFetchException("Could not reach the mail provider.", e);
            }
        };
    }

    @Bean
    public MailFetcher gmailFetcher(MailHttp http, MailProperties properties) {
        return new GmailFetcher(http, properties.gmailBase());
    }

    @Bean
    public MailFetcher graphFetcher(MailHttp http, MailProperties properties) {
        return new GraphFetcher(http, properties.graphBase());
    }
}
