package com.onionmind.search;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The versioned index against a real Qdrant (replace-ollama-with-gemini-deepseek D7): a new
 * collection is created with the configured dimension, a different one fails the boot, and
 * another model's point is neither "unchanged" nor a search hit. A hit carries the chunk text
 * from the payload, or null for a point written without it (phase5-rag-chat D3).
 */
class QdrantVersionedIndexTest {

    private static final GenericContainer<?> QDRANT =
        new GenericContainer<>("qdrant/qdrant:v1.11.0").withExposedPorts(6333);

    @BeforeAll
    static void start() {
        QDRANT.start();
    }

    @AfterAll
    static void stop() {
        QDRANT.stop();
    }

    private static QdrantVectorStore store(String collection, int dims, String model) {
        return new QdrantVectorStore(QDRANT.getHost(), QDRANT.getMappedPort(6333), "", collection, dims, model);
    }

    private static EmbeddingPoint point(String url, float[] vector) {
        return new EmbeddingPoint(UUID.nameUUIDFromBytes((url + "-0").getBytes()), vector, url, 0, "hash-1", "chunk");
    }

    @Test
    void missingCollectionIsCreatedWithTheConfiguredDimension() {
        QdrantVectorStore store = store("created_v2", 4, "gemini-embedding-2");

        store.ensureCollectionOnStartup();
        store.upsert(List.of(point("http://a.onion/", new float[]{1, 0, 0, 0})));

        assertThat(store.search(new float[]{1, 0, 0, 0}, 5)).extracting(SemanticSearchHit::url)
            .containsExactly("http://a.onion/");
        store.ensureCollection(); // second boot: same dimension, nothing to do
    }

    @Test
    void existingCollectionWithAnotherDimensionFailsTheBootNamingBoth() {
        store("legacy_chunks", 768, "nomic-embed-text").ensureCollection();
        QdrantVectorStore misconfigured = store("legacy_chunks", 4, "gemini-embedding-2");

        assertThatThrownBy(misconfigured::ensureCollectionOnStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("legacy_chunks").hasMessageContaining("768").hasMessageContaining("4");
    }

    @Test
    void anotherModelsPointIsReembeddedAndNeverSearched() {
        QdrantVectorStore oldModel = store("shared_chunks", 4, "nomic-embed-text");
        QdrantVectorStore newModel = store("shared_chunks", 4, "gemini-embedding-2");
        oldModel.ensureCollection();
        oldModel.upsert(List.of(point("http://old.onion/", new float[]{1, 0, 0, 0})));

        assertThat(oldModel.hasUnchangedEmbedding("http://old.onion/", "hash-1")).isTrue();
        assertThat(newModel.hasUnchangedEmbedding("http://old.onion/", "hash-1")).isFalse();
        assertThat(newModel.search(new float[]{1, 0, 0, 0}, 5)).isEmpty();

        newModel.upsert(List.of(point("http://old.onion/", new float[]{0, 1, 0, 0})));
        assertThat(newModel.hasUnchangedEmbedding("http://old.onion/", "hash-1")).isTrue();
        assertThat(newModel.search(new float[]{0, 1, 0, 0}, 5)).extracting(SemanticSearchHit::url)
            .containsExactly("http://old.onion/");
    }

    @Test
    void searchReadsTheChunkTextOrNullForAPointWithoutIt() throws Exception {
        QdrantVectorStore store = store("text_chunks", 4, "gemini-embedding-2");
        store.ensureCollection();
        store.upsert(List.of(new EmbeddingPoint(UUID.nameUUIDFromBytes("with".getBytes()), new float[]{1, 0, 0, 0},
            "http://with.onion/", 0, "hash-1", "the chunk text")));
        // a point from before the payload had text, written by hand
        String legacy = """
            {"points":[{"id":"%s","vector":[0.9,0.1,0,0],
              "payload":{"url":"http://without.onion/","chunk_index":3,"content_hash":"h","model":"gemini-embedding-2"}}]}
            """.formatted(UUID.nameUUIDFromBytes("without".getBytes()));
        try (HttpClient client = HttpClient.newHttpClient()) {
            URI uri = URI.create("http://%s:%d/collections/text_chunks/points?wait=true"
                .formatted(QDRANT.getHost(), QDRANT.getMappedPort(6333)));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(legacy)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }

        assertThat(store.search(new float[]{1, 0, 0, 0}, 5))
            .extracting(SemanticSearchHit::url, SemanticSearchHit::text)
            .containsExactly(
                tuple("http://with.onion/", "the chunk text"),
                tuple("http://without.onion/", null));
    }
}
