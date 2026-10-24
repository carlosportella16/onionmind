package com.onionmind.ai;

import com.onionmind.ai.provider.ProviderException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one place that talks to an embedding model: Gemini's {@code batchEmbedContents}
 * (replace-ollama-with-gemini-deepseek D6). Both DefaultAIOrchestrator and the
 * embeddings-only EmbeddingOnlyAIOrchestrator delegate here. {@code gemini-embedding-2} has
 * no task type, so the role goes in the text with the prefixes the model documents — the
 * same format on both sides, or queries and chunks stop matching. Deterministic, so
 * confidence is always 1.0.
 */
@Component
class GeminiEmbedder {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String QUERY = "task: search result | query: %s";
    private static final String DOCUMENT = "title: %s | text: %s";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final int batchSize;

    GeminiEmbedder(@Value("${ai.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
                   @Value("${ai.gemini.api-key:}") String apiKey,
                   @Value("${embedding.model:gemini-embedding-2}") String model,
                   @Value("${embedding.dimensions:768}") int dimensions,
                   @Value("${embedding.request-batch-size:100}") int batchSize) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = batchSize;
    }

    /**
     * One vector per text, in order. {@code query} marks a search query; otherwise each text is
     * a chunk of the document named {@code title} ({@code null} → "none", as the model asks).
     */
    List<Embedding> embed(List<String> texts, String title, boolean query) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException("gemini api key not configured (embeddings need it)", false, false);
        }
        List<Embedding> out = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + batchSize));
            out.addAll(embedBatch(batch.stream()
                .map(t -> query ? QUERY.formatted(t) : DOCUMENT.formatted(title == null ? "none" : title, t))
                .toList()));
        }
        return out;
    }

    private List<Embedding> embedBatch(List<String> texts) {
        List<Map<String, Object>> requests = texts.stream().<Map<String, Object>>map(t -> Map.of(
            "model", "models/" + model,
            "content", Map.of("parts", List.of(Map.of("text", t))),
            "outputDimensionality", dimensions)).toList();
        JsonNode embeddings = post(Map.of("requests", requests)).path("embeddings");
        if (embeddings.size() != texts.size()) {
            throw new ProviderException("gemini returned " + embeddings.size() + " embeddings for "
                + texts.size() + " texts", true, false);
        }
        List<Embedding> out = new ArrayList<>(texts.size());
        for (JsonNode e : embeddings) {
            JsonNode values = e.path("values");
            if (values.size() != dimensions) {
                throw new ProviderException("gemini returned a " + values.size() + "-dimension embedding, expected "
                    + dimensions, false, false);
            }
            float[] vector = new float[values.size()];
            int i = 0;
            for (JsonNode v : values) {
                vector[i++] = (float) v.asDouble();
            }
            out.add(new Embedding(vector, 1.0));
        }
        return out;
    }

    private JsonNode post(Object body) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models/" + model + ":batchEmbedContents"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ProviderException("gemini embed request failed", e);
        }
        if (response.statusCode() >= 400) {
            String error = response.body() == null ? "" : response.body();
            throw new ProviderException("gemini embed HTTP " + response.statusCode() + " - "
                + error.substring(0, Math.min(200, error.length())), response.statusCode() >= 500, false);
        }
        return mapper.readTree(response.body());
    }
}
