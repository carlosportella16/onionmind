package com.onionmind.ai;

import com.onionmind.ai.provider.ProviderException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiEmbedderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final int DIMS = 3;

    private FakeHttpServer server;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        // Echoes one vector per request, whose first value is the request's position in the batch.
        server.responder = (path, body) -> {
            JsonNode requests = MAPPER.readTree(body).path("requests");
            List<Object> embeddings = new ArrayList<>();
            for (int i = 0; i < requests.size(); i++) {
                embeddings.add(Map.of("values", List.of(i, 0.5, 0.25)));
            }
            return new FakeHttpServer.Reply(200, MAPPER.writeValueAsString(Map.of("embeddings", embeddings)));
        };
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private GeminiEmbedder embedder(int batchSize) {
        return new GeminiEmbedder(server.baseUrl(), "test-key", "gemini-embedding-2", DIMS, batchSize);
    }

    private JsonNode request(int n) {
        return MAPPER.readTree(server.requestBodies.get(n)).path("requests");
    }

    @Test
    void thirtyChunksAreOneBatchRequestInChunkOrder() {
        List<String> chunks = IntStream.range(0, 30).mapToObj(i -> "chunk " + i).toList();

        List<Embedding> out = embedder(100).embed(chunks, "http://x.onion/", false);

        assertThat(server.requestPaths).containsExactly("/models/gemini-embedding-2:batchEmbedContents");
        assertThat(server.authHeaders).containsExactly("test-key");
        assertThat(request(0)).hasSize(30);
        assertThat(request(0).get(7).path("content").path("parts").path(0).path("text").asString())
            .isEqualTo("title: http://x.onion/ | text: chunk 7");
        assertThat(request(0).get(0).path("model").asString()).isEqualTo("models/gemini-embedding-2");
        assertThat(request(0).get(0).path("outputDimensionality").asInt()).isEqualTo(DIMS);
        assertThat(out).hasSize(30);
        assertThat(out.get(29).vector()).containsExactly(29f, 0.5f, 0.25f);
        assertThat(out.get(0).confidence()).isEqualTo(1.0);
    }

    @Test
    void moreTextsThanTheBatchSizeSplitIntoSeveralRequestsKeepingOrder() {
        List<Embedding> out = embedder(2).embed(List.of("a", "b", "c"), null, false);

        assertThat(server.requestPaths).hasSize(2);
        assertThat(request(1)).hasSize(1);
        assertThat(out).extracting(e -> e.vector()[0]).containsExactly(0f, 1f, 0f);
    }

    @Test
    void queriesAndUntitledChunksGetTheirRolePrefixes() {
        embedder(10).embed(List.of("criptomoedas"), "ignored for queries", true);
        embedder(10).embed(List.of("chunk"), null, false);

        assertThat(request(0).get(0).at("/content/parts/0/text").asString())
            .isEqualTo("task: search result | query: criptomoedas");
        assertThat(request(1).get(0).at("/content/parts/0/text").asString()).isEqualTo("title: none | text: chunk");
    }

    @Test
    void httpFailureThrowsProviderException() {
        server.responder = null;
        server.status = 500;
        server.responseBody = "{\"error\":{\"message\":\"internal\"}}";

        assertThatThrownBy(() -> embedder(10).embed(List.of("x"), null, false))
            .isInstanceOfSatisfying(ProviderException.class, e -> {
                assertThat(e).hasMessageContaining("500");
                assertThat(e.retryable()).isTrue();
            });
    }

    @Test
    void aShortOrWronglySizedAnswerIsAFailureNotAPartialResult() {
        server.responder = (path, body) -> new FakeHttpServer.Reply(200, "{\"embeddings\":[{\"values\":[1,2,3]}]}");
        assertThatThrownBy(() -> embedder(10).embed(List.of("a", "b"), null, false))
            .isInstanceOf(ProviderException.class).hasMessageContaining("1 embeddings for 2 texts");

        server.responder = (path, body) -> new FakeHttpServer.Reply(200, "{\"embeddings\":[{\"values\":[1,2]}]}");
        assertThatThrownBy(() -> embedder(10).embed(List.of("a"), null, false))
            .isInstanceOf(ProviderException.class).hasMessageContaining("2-dimension");
    }

    @Test
    void missingApiKeyRefusesWithoutCallingOut() {
        GeminiEmbedder noKey = new GeminiEmbedder(server.baseUrl(), " ", "gemini-embedding-2", DIMS, 10);

        assertThatThrownBy(() -> noKey.embed(List.of("x"), null, false)).isInstanceOf(ProviderException.class);
        assertThat(server.requestPaths).isEmpty();
    }

    @Test
    void embeddingOnlyOrchestratorEmbedsByRoleAndRefusesGeneration() {
        var orchestrator = new EmbeddingOnlyAIOrchestrator(embedder(10));
        TaskContext query = new TaskContext(TaskContext.TaskType.EMBED, 10, null, false, true, null, 0);

        assertThat(orchestrator.embed("criptomoedas", query).vector()).hasSize(DIMS);
        assertThat(orchestrator.embedAll(List.of("a", "b"), "http://x.onion/",
            TaskContext.batch(TaskContext.TaskType.EMBED, 1, null, false))).hasSize(2);
        assertThat(request(0).get(0).at("/content/parts/0/text").asString()).startsWith("task: search result");
        assertThat(request(1).get(0).at("/content/parts/0/text").asString()).startsWith("title: http://x.onion/");

        assertThatThrownBy(() -> orchestrator.summarize("text", query)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> orchestrator.classify("text", query)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> orchestrator.translate("text", "pt", query))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> orchestrator.detectLanguage("text", query))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> orchestrator.extractEntities("text", query))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
