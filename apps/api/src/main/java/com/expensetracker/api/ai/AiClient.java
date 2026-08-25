package com.expensetracker.api.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one place a model is spoken to.
 *
 * <p>Three rules govern everything here, and each exists because the obvious
 * alternative is worse.
 *
 * <p><strong>It never throws.</strong> Every failure — no key, budget spent,
 * timeout, rate limit, malformed JSON, a provider returning HTML — comes back
 * as an empty {@link Optional}. Callers are fallbacks by construction: the
 * deterministic answer already exists and stands when this returns nothing. An
 * exception escaping here would turn "the AI could not help" into "reading your
 * mail failed", which is a much worse thing to tell somebody.
 *
 * <p><strong>It redacts, itself.</strong> {@link Redactor#scrub} runs inside
 * this class rather than only at call sites, because a call site that forgets
 * is a privacy incident and there is no reason to leave that possible. Scrubbing
 * is idempotent, so a caller that has already cleaned its text loses nothing.
 *
 * <p><strong>It counts before it spends.</strong> The budget is checked against
 * the database, not memory. A free instance restarts several times a day and an
 * in-memory counter would reset with it, which is precisely the moment a stuck
 * retry loop would take advantage of.
 */
@Component
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    /**
     * Low enough that the model cannot ramble, high enough for a JSON object
     * with a merchant name in it. Also a cost ceiling per call.
     */
    private static final int MAX_TOKENS = 400;

    /**
     * Zero temperature. This is extraction, not writing: given the same alert
     * twice the answer must be the same both times, or a retry would silently
     * produce a different transaction.
     */
    private static final double TEMPERATURE = 0.0;

    private final AiProperties properties;
    private final AiBudget budget;
    private final ObjectMapper json;
    private final RestClient http;

    public AiClient(AiProperties properties, AiBudget budget, ObjectMapper json) {
        this.properties = properties;
        this.budget = budget;
        this.json = json;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(10, properties.timeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));

        this.http = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(properties.baseUrl())
                .build();
    }

    /** Whether asking is worth attempting at all, before any work is done. */
    public boolean isAvailable() {
        return properties.isUsable();
    }

    /**
     * Asks for one JSON object.
     *
     * <p>The instruction and the data are kept in separate roles rather than
     * concatenated. It is not a security boundary — nothing stops a model
     * following instructions in a bank alert — but it is the strongest
     * separation the API offers, and the alternative gives up even that.
     *
     * @param userId  whose budget this comes out of
     * @param purpose a short label for logs; never contains user data
     * @param system  the instruction. Must be a constant defined in code and
     *                never assembled from anything a message supplied: unlike
     *                {@code user} it is not scrubbed, so a value derived from
     *                an email would carry that email's text out unredacted and
     *                sit above it in the prompt, where it is likelier to be
     *                obeyed. Every current caller passes a literal.
     * @param user    the data, which is scrubbed before it leaves
     * @return the parsed object, or empty for every possible failure
     */
    public Optional<JsonNode> completeJson(UUID userId, String purpose, String system, String user) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        if (!budget.tryConsume(userId, properties.dailyCallBudget())) {
            log.info("AI budget for today is spent; skipping {}", purpose);
            return Optional.empty();
        }

        String scrubbed = Redactor.scrub(user);

        try {
            JsonNode response = http.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "model", properties.model(),
                            "temperature", TEMPERATURE,
                            "max_tokens", MAX_TOKENS,
                            "response_format", Map.of("type", "json_object"),
                            "messages", List.of(
                                    Map.of("role", "system", "content", system),
                                    Map.of("role", "user", "content", scrubbed))))
                    .retrieve()
                    .body(JsonNode.class);

            recordTokens(userId, response);
            return content(response).flatMap(this::parse);

        } catch (RestClientException e) {
            // Includes timeouts, 429s, 5xxs and DNS failures. All of them mean
            // the same thing to a caller — no answer — and none of them are
            // worth a stack trace in the log of a personal finance app.
            log.warn("AI call for {} did not complete: {}", purpose, e.getMessage());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("AI call for {} failed unexpectedly: {}", purpose, e.toString());
            return Optional.empty();
        }
    }

    private Optional<String> content(JsonNode response) {
        if (response == null) {
            return Optional.empty();
        }
        JsonNode text = response.path("choices").path(0).path("message").path("content");
        return text.isTextual() && !text.asText().isBlank()
                ? Optional.of(text.asText())
                : Optional.empty();
    }

    /**
     * Reads the model's reply as JSON.
     *
     * <p>Tolerates the two things models do even when told not to: wrapping the
     * object in a markdown fence, and adding a sentence before it. Anything
     * else is discarded rather than repaired — a half-understood object is
     * worse than no object, because it would be acted on.
     */
    private Optional<JsonNode> parse(String raw) {
        String text = raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstNewline > 0 && closing > firstNewline) {
                text = text.substring(firstNewline + 1, closing).strip();
            }
        }

        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return Optional.empty();
        }

        try {
            JsonNode node = json.readTree(text.substring(open, close + 1));
            return node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /**
     * Records what the call cost.
     *
     * <p>Best effort on purpose: several providers omit usage entirely, and a
     * missing field must not fail a call that has already succeeded. The call
     * itself was counted before it was made, which is the number the cap
     * actually uses.
     */
    private void recordTokens(UUID userId, JsonNode response) {
        if (response == null) {
            return;
        }
        JsonNode usage = response.path("usage");
        long in = usage.path("prompt_tokens").asLong(0);
        long out = usage.path("completion_tokens").asLong(0);
        if (in > 0 || out > 0) {
            budget.recordTokens(userId, in, out);
        }
    }
}
