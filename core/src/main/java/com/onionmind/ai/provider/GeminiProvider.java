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
 * goes in {@code systemInstruction}, the output is JSON, and thinking is off so no call pays
 * for reasoning tokens (replace-ollama-with-gemini-deepseek D2; budget 0 is valid on 2.5
 * Flash-Lite, a Gemini 3 model would need {@code thinkingLevel} instead).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class GeminiProvider extends HttpAIProvider {

    private static final Map<String, Object> GENERATION_CONFIG = Map.of(
        "responseMimeType", "application/json",
        "thinkingConfig", Map.of("thinkingBudget", 0));

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
        return new CompletionResponse(text);
    }

    /**
     * A 429 lists the quotas it hit in {@code error.details[].violations[].quotaId}, e.g.
     * {@code GenerateRequestsPerDayPerProjectPerModel}: any per-day id parks until the quota day ends.
     */
    @Override
    protected ProviderException.Quota quotaOf429(String body) {
        try {
            for (JsonNode detail : mapper.readTree(body).path("error").path("details")) {
                for (JsonNode violation : detail.path("violations")) {
                    if (violation.path("quotaId").asString("").contains("PerDay")) {
                        return ProviderException.Quota.DAY;
                    }
                }
            }
        } catch (Exception e) {
            // unparseable 429 body: fall back to the shortest park
        }
        return ProviderException.Quota.MINUTE;
    }
}
