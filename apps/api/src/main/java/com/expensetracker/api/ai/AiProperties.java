package com.expensetracker.api.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about the model, and whether there is one at all.
 *
 * <p>Defaults to <strong>off</strong>, and that is the important part. Every
 * feature built on this has a deterministic path that runs first and a defined
 * behaviour when the model is unavailable — so an installation with no API key
 * is not a degraded installation, it is the normal one. The AI is a fallback
 * for the cases rules cannot reach, never a dependency.
 *
 * <p>The endpoint is configurable because the OpenAI chat-completions shape is
 * effectively a lingua franca: Groq, OpenRouter, Together, DeepSeek, Mistral
 * and a local Ollama all speak it. One client reaches all of them, and a
 * personal project can point at whichever still has a free tier this month
 * without a code change. It also means the whole path can be pointed at a stub
 * and tested, which is the only reason any of this code is reachable by a test.
 *
 * @param dailyCallBudget hard per-user daily ceiling on model calls
 * @param minConfidence   below this, a model's answer is never acted on
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        int dailyCallBudget,
        double minConfidence) {

    public AiProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com/v1"
                : baseUrl.replaceAll("/+$", "");
        model = model == null || model.isBlank() ? "gpt-4o-mini" : model.strip();

        // A timeout is not optional. This sits inside the parse loop, and a
        // model that never answers would hold a database transaction open and
        // stall every message behind it.
        timeoutSeconds = timeoutSeconds <= 0 || timeoutSeconds > 120 ? 20 : timeoutSeconds;

        // Zero would be read as "no calls" rather than "no limit", which is the
        // safer reading of an accidentally empty setting.
        dailyCallBudget = dailyCallBudget < 0 ? 0 : dailyCallBudget;

        // Clamped rather than rejected, but never to zero: a threshold of zero
        // would accept anything the model said, which defeats the gate this
        // number exists to be.
        minConfidence = minConfidence <= 0 || minConfidence > 1 ? 0.75 : minConfidence;
    }

    /**
     * Whether a call can actually be made.
     *
     * <p>Enabled without a key is a misconfiguration, not a request to try
     * anyway — every call would fail, slowly, once per message.
     */
    public boolean isUsable() {
        return enabled && apiKey != null && !apiKey.isBlank() && dailyCallBudget > 0;
    }

    /** Why the AI is off, in words a user can act on. */
    public String unavailableReason() {
        if (!enabled) {
            return "AI features are switched off on this server.";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "AI features are switched on but no API key is configured.";
        }
        if (dailyCallBudget <= 0) {
            return "AI features are switched on but the daily budget is zero.";
        }
        return null;
    }
}
