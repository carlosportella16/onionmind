package com.onionmind.ai.provider;

import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Groq via its OpenAI-compatible {@code /chat/completions}. Free, very low latency,
 * daily + per-minute ceiling — first rung for long/critical text (SDD sec. 7.3).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class GroqProvider extends HttpAIProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final ProviderQuotaTracker quotaTracker;

    public GroqProvider(@Value("${ai.groq.base-url}") String baseUrl,
                        @Value("${ai.groq.model}") String model,
                        @Value("${ai.groq.api-key}") String apiKey,
                        ProviderQuotaTracker quotaTracker) {
        super(Duration.ofSeconds(60));
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.quotaTracker = quotaTracker;
    }

    @Override
    public String id() {
        return "groq";
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException("groq api key not configured", false, false);
        }

        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));
        Object body = Map.of("model", model, "messages", messages);

        try {
            JsonNode root = postJson(baseUrl + "/chat/completions",
                Map.of("Authorization", "Bearer " + apiKey), body);
            String content = root.path("choices").path(0).path("message").path("content").asString("");
            return new CompletionResponse(content);
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
