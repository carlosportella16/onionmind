package com.onionmind.search;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin REST client for Qdrant (SDD Fase 2, sec. 3.2). Talks HTTP directly instead of a
 * generated client so the module has no third-party runtime dependency beyond Jackson,
 * which Spring Boot already provides.
 *
 * <p>Vectors of different models never mix (replace-ollama-with-gemini-deepseek D7): each
 * model gets its own collection ({@code qdrant.collection}), every point records the model
 * that made it, and a point of another model is neither "unchanged" nor a search hit.
 */
@Component
@ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "true")
public class QdrantVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final String baseUrl;
    private final String apiKey;
    private final String collection;
    private final int vectorSize;
    private final String model;

    public QdrantVectorStore(@Value("${qdrant.url}") String host,
                              @Value("${qdrant.port}") int port,
                              @Value("${qdrant.api-key:}") String apiKey,
                              @Value("${qdrant.collection}") String collection,
                              @Value("${qdrant.vector-size}") int vectorSize,
                              @Value("${embedding.model}") String model) {
        this.baseUrl = "http://" + host + ":" + port;
        this.apiKey = apiKey;
        this.collection = collection;
        this.vectorSize = vectorSize;
        this.model = model;
    }

    /**
     * Runs on startup so the collection exists before the first embedding is upserted — without
     * this, every upsert 404s forever, since Qdrant never creates collections implicitly.
     * Swallows Qdrant being unreachable at boot so a transient outage doesn't stop the app — but
     * not a dimension mismatch, which would silently corrupt every search: that fails the boot.
     */
    @PostConstruct
    void ensureCollectionOnStartup() {
        try {
            ensureCollection();
        } catch (QdrantException e) {
            log.warn("Could not ensure Qdrant collection '{}' on startup: {}", collection, e.getMessage());
        }
    }

    /**
     * Creates the collection with the configured dimension if it doesn't exist yet; safe to call
     * on every startup. Throws IllegalStateException when it exists with another dimension.
     */
    public void ensureCollection() {
        Integer existing = existingVectorSize();
        if (existing == null) {
            Map<String, Object> body = Map.of("vectors", Map.of("size", vectorSize, "distance", "Cosine"));
            send("PUT", "/collections/" + collection, body);
        } else if (existing != vectorSize) {
            throw new IllegalStateException("Qdrant collection '%s' has %d-dimension vectors but %d are configured "
                .formatted(collection, existing, vectorSize)
                + "— point qdrant.collection at a new collection for the new embedding model");
        }
    }

    /** The collection's vector size, or null when it doesn't exist. */
    private Integer existingVectorSize() {
        HttpResponse<String> response;
        try {
            response = execute(newRequestBuilder("/collections/" + collection).GET());
        } catch (Exception e) {
            throw new QdrantException("Qdrant GET /collections/" + collection + " failed", e);
        }
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() >= 400) {
            throw new QdrantException("Qdrant GET /collections/" + collection + " failed: HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body()).path("result").path("config").path("params")
            .path("vectors").path("size").asInt();
    }

    /** Idempotent insert/update keyed by point id. */
    public void upsert(List<EmbeddingPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        List<Map<String, Object>> pointBodies = new ArrayList<>();
        for (EmbeddingPoint p : points) {
            pointBodies.add(Map.of(
                "id", p.id().toString(),
                "vector", toFloatList(p.vector()),
                "payload", Map.of(
                    "url", p.url(),
                    "chunk_index", p.chunkIndex(),
                    "content_hash", p.contentHash(),
                    "model", model,
                    "text", p.text()
                )
            ));
        }
        // wait=true: the page is only marked embedded once its points are searchable
        send("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", pointBodies));
    }

    public List<SemanticSearchHit> search(float[] queryVector, int topK) {
        Map<String, Object> body = Map.of(
            "vector", toFloatList(queryVector),
            "limit", topK,
            "with_payload", true,
            "filter", Map.of("must", List.of(sameModel()))
        );
        JsonNode response = send("POST", "/collections/" + collection + "/points/search", body);
        List<SemanticSearchHit> hits = new ArrayList<>();
        for (JsonNode item : response.path("result")) {
            JsonNode payload = item.path("payload");
            hits.add(new SemanticSearchHit(
                payload.path("url").asString(),
                payload.path("chunk_index").asInt(),
                item.path("score").asDouble(),
                payload.path("text").asString(null)
            ));
        }
        return hits;
    }

    /**
     * Cost gate (SDD Fase 2, sec. 3.5): true if this url already has a chunk with the same content
     * hash, embedded by the configured model — another model's point means re-embed.
     */
    public boolean hasUnchangedEmbedding(String url, String contentHash) {
        Map<String, Object> filter = Map.of(
            "must", List.of(
                Map.of("key", "url", "match", Map.of("value", url)),
                Map.of("key", "content_hash", "match", Map.of("value", contentHash)),
                sameModel()
            )
        );
        Map<String, Object> body = Map.of("filter", filter, "limit", 1);
        JsonNode response = send("POST", "/collections/" + collection + "/points/scroll", body);
        return response.path("result").path("points").size() > 0;
    }

    public void deleteByUrl(String url) {
        Map<String, Object> filter = Map.of("must", List.of(Map.of("key", "url", "match", Map.of("value", url))));
        send("POST", "/collections/" + collection + "/points/delete", Map.of("filter", filter));
    }

    private Map<String, Object> sameModel() {
        return Map.of("key", "model", "match", Map.of("value", model));
    }

    private List<Float> toFloatList(float[] vector) {
        List<Float> list = new ArrayList<>(vector.length);
        for (float f : vector) {
            list.add(f);
        }
        return list;
    }

    private JsonNode send(String method, String path, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest.Builder builder = newRequestBuilder(path)
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json");
            HttpResponse<String> response = execute(builder);
            if (response.statusCode() >= 400) {
                throw new QdrantException(
                    "Qdrant " + method + " " + path + " failed: HTTP " + response.statusCode() + " - " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (QdrantException e) {
            throw e;
        } catch (Exception e) {
            throw new QdrantException("Qdrant " + method + " " + path + " failed", e);
        }
    }

    private HttpRequest.Builder newRequestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(30));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("api-key", apiKey);
        }
        return builder;
    }

    private HttpResponse<String> execute(HttpRequest.Builder builder) throws Exception {
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
