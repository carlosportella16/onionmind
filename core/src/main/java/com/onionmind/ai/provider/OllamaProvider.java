package com.onionmind.ai.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local generation via Ollama's {@code /api/generate}. No rate limit — this is always
 * the last rung of the ladder and never gated by quota (SDD sec. 7.3).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class OllamaProvider extends HttpAIProvider {

    private final String url;
    private final String model;

    public OllamaProvider(@Value("${ollama.url}") String url,
                          @Value("${ai.ollama.generate-model}") String model,
                          @Value("${ai.ollama.timeout}") Duration timeout) {
        super(timeout);
        this.url = url;
        this.model = model;
    }

    @Override
    public String id() {
        return "ollama";
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", request.userPrompt());
        body.put("stream", false);
        if (request.systemPrompt() != null) {
            body.put("system", request.systemPrompt());
        }

        JsonNode root = postJson(url + "/api/generate", Map.of(), body);
        return new CompletionResponse(root.path("response").asString(""));
    }

    @Override
    public ProviderQuota currentQuota() {
        return ProviderQuota.UNLIMITED;
    }
}
