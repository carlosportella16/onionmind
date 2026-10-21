package com.onionmind.ai;

import org.springframework.beans.factory.annotation.Value;
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
 * The one place that talks to Ollama's {@code /api/embed}. Both the Fase 2
 * OllamaAIOrchestrator and the Fase 3 DefaultAIOrchestrator delegate {@code embed()} here
 * so there is a single embedding implementation. Ollama's embedding model is deterministic,
 * so confidence is always 1.0.
 */
@Component
public class OllamaEmbedder {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String url;
    private final String model;

    public OllamaEmbedder(@Value("${ollama.url:http://localhost:11434}") String url,
                          @Value("${ollama.model:nomic-embed-text}") String model) {
        this.url = url;
        this.model = model;
    }

    public Embedding embed(String text) {
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

            JsonNode vectorNode = mapper.readTree(response.body()).path("embeddings").path(0);
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
