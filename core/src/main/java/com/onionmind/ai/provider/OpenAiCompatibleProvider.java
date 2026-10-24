package com.onionmind.ai.provider;

import com.onionmind.ai.routing.ProviderQuotaTracker;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Any provider speaking OpenAI's {@code /chat/completions}, declared under
 * {@code ai.openai-compatible.<id>} — one bean per entry, registered by
 * {@link OpenAiCompatibleProviders}. {@code extra-body} is merged into every request: for
 * DeepSeek that is JSON mode plus thinking off (replace-ollama-with-gemini-deepseek D1).
 */
public class OpenAiCompatibleProvider extends HttpAIProvider {

    /** One {@code ai.openai-compatible.<id>} entry. */
    public record Settings(String baseUrl, String model, String apiKey, Map<String, Object> extraBody) {
    }

    private final String id;
    private final Settings settings;

    public OpenAiCompatibleProvider(String id, Settings settings, ProviderQuotaTracker quotaTracker) {
        super(Duration.ofSeconds(60), settings.apiKey(), quotaTracker);
        this.id = id;
        this.settings = settings;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    protected CompletionResponse call(CompletionRequest request) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.systemPrompt() != null) {
            messages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        messages.add(Map.of("role", "user", "content", request.userPrompt()));
        Map<String, Object> body = new LinkedHashMap<>();
        if (settings.extraBody() != null) {
            body.putAll(settings.extraBody());
        }
        body.put("model", settings.model());
        body.put("messages", messages);

        JsonNode root = postJson(settings.baseUrl() + "/chat/completions",
            Map.of("Authorization", "Bearer " + apiKey), body);
        JsonNode tokens = root.path("usage").path("total_tokens");
        return new CompletionResponse(root.path("choices").path(0).path("message").path("content").asString(""),
            tokens.isNumber() ? tokens.asInt() : null);
    }
}
