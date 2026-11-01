package com.onionmind.ai.provider;

import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini via {@code /models/{model}:generateContent} — paid tier only: the free tier uses
 * the content to improve products, which is not approved for .onion text. The system prompt
 * goes in {@code systemInstruction}, the output is JSON, and thinking stays at its lowest level
 * so calls don't pay for reasoning tokens. Gemini 3 has no "off": {@code thinkingLevel: minimal}
 * is the floor, and {@code thinkingBudget: 0} is rejected with HTTP 400 (checked live on
 * gemini-3.5-flash-lite, 2026-10-24 — gemini-2.5-flash-lite is no longer open to new users).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class GeminiProvider extends HttpAIProvider {

    private static final Map<String, Object> GENERATION_CONFIG = Map.of(
        "responseMimeType", "application/json",
        "thinkingConfig", Map.of("thinkingLevel", "minimal"));

    private final String baseUrl;
    private final String model;

    public GeminiProvider(@Value("${ai.gemini.base-url}") String baseUrl,
                          @Value("${ai.gemini.model}") String model,
                          @Value("${ai.gemini.api-key}") String apiKey,
                          ProviderQuotaTracker quotaTracker) {
        super(Duration.ofSeconds(60), apiKey, quotaTracker);
        this.baseUrl = baseUrl;
        this.model = model;
    }

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    protected CompletionResponse call(CompletionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.systemPrompt() != null) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", request.systemPrompt()))));
        }
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", request.userPrompt())))));
        body.put("generationConfig", GENERATION_CONFIG);

        JsonNode root = postJson(baseUrl + "/models/" + model + ":generateContent",
            Map.of("x-goog-api-key", apiKey), body);
        String text = root.path("candidates").path(0).path("content").path("parts").path(0)
            .path("text").asString("");
        JsonNode tokens = root.path("usageMetadata").path("totalTokenCount");
        return new CompletionResponse(text, tokens.isNumber() ? tokens.asInt() : null);
    }
}
