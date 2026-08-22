package com.onionmind.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Embeddings via Ollama local (SDD Fase 2, sec. 3.1). Ollama's models are deterministic,
 * so confidence is always 1.0. summarize() stays unimplemented — that's Fase 3 scope.
 */
@Component
@ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "true")
public class OllamaAIOrchestrator implements AIOrchestrator {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String url;
    private final String model;

    public OllamaAIOrchestrator(@Value("${ollama.url}") String url,
                                 @Value("${ollama.model}") String model) {
        this.url = url;
        this.model = model;
    }

    @Override
    public Summary summarize(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("Sumarização chega na Fase 3 — ver SDD seção 4.2");
    }

    @Override
    public Embedding embed(String text, TaskContext ctx) {
        Map<String, Object> body = Map.of("model", model, "input", text);
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "/api/embed"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new OllamaException("Ollama embed failed: HTTP " + response.statusCode() + " - " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode vectorNode = root.path("embeddings").path(0);
            float[] vector = new float[vectorNode.size()];
            int i = 0;
            for (JsonNode v : vectorNode) {
                vector[i++] = (float) v.asDouble();
            }
            return new Embedding(vector, 1.0);
        } catch (OllamaException e) {
            throw e;
        } catch (Exception e) {
            throw new OllamaException("Ollama embed failed", e);
        }
    }
}
