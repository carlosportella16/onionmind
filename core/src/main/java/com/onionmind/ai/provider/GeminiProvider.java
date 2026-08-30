package com.onionmind.ai.provider;

import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gemini Flash via {@code /models/{model}:generateContent}. Free, 1M-token context,
 * tighter daily cap — second cloud rung, and the one that swallows oversized text.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class GeminiProvider extends HttpAIProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final ProviderQuotaTracker quotaTracker;

    public GeminiProvider(@Value("${ai.gemini.base-url}") String baseUrl,
                          @Value("${ai.gemini.model}") String model,
                          @Value("${ai.gemini.api-key}") String apiKey,
                          ProviderQuotaTracker quotaTracker) {
        super(Duration.ofSeconds(60));
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.quotaTracker = quotaTracker;
    }

    @Override
    public String id() {
        return "gemini";
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException("gemini api key not configured", false, false);
        }

        String prompt = request.systemPrompt() == null
            ? request.userPrompt()
            : request.systemPrompt() + "\n\n" + request.userPrompt();
        Object body = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));

        try {
            JsonNode root = postJson(baseUrl + "/models/" + model + ":generateContent",
                Map.of("x-goog-api-key", apiKey), body);
            String text = root.path("candidates").path(0).path("content").path("parts").path(0)
                .path("text").asString("");
            return new CompletionResponse(text);
        } catch (ProviderException e) {
            if (e.rateLimited()) {
                quotaTracker.markExhausted(id());
            }
            throw e;
        }
    }

    @Override
    public ProviderQuota currentQuota() {
        if (apiKey == null || apiKey.isBlank()) {
            return new ProviderQuota(0, 0);
        }
        return quotaTracker.remaining(id());
    }
}
