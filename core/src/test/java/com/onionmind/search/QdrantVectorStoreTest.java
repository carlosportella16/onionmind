package com.onionmind.search;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QdrantVectorStoreTest {

    private HttpServer server;
    private QdrantVectorStore store;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestMethod = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private volatile String nextResponseBody = "{}";
    private volatile int nextResponseStatus = 200;

    @BeforeEach
    void startFakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastRequestMethod.set(exchange.getRequestMethod());
            lastRequestPath.set(exchange.getRequestURI().getPath());
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastRequestBody.set(new String(body, StandardCharsets.UTF_8));

            byte[] response = nextResponseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextResponseStatus, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        store = new QdrantVectorStore("127.0.0.1", port, "", "page_chunks", 3);
    }

    @AfterEach
    void stopFakeServer() {
        server.stop(0);
    }

    @Test
    void upsertSendsPointsToQdrant() {
        nextResponseBody = "{\"status\":\"ok\"}";
        UUID id = UUID.randomUUID();
        EmbeddingPoint point = new EmbeddingPoint(id, new float[]{0.1f, 0.2f, 0.3f}, "http://x.onion", 0, "hash1");

        store.upsert(List.of(point));

        assertThat(lastRequestMethod.get()).isEqualTo("PUT");
        assertThat(lastRequestPath.get()).isEqualTo("/collections/page_chunks/points");
        assertThat(lastRequestBody.get()).contains(id.toString()).contains("hash1").contains("http://x.onion");
    }

    @Test
    void upsertWithNoPointsDoesNotCallServer() {
        store.upsert(List.of());

        assertThat(lastRequestBody.get()).isNull();
    }

    @Test
    void searchParsesHitsFromResponse() {
        nextResponseBody = """
            {"result":[
              {"score":0.91,"payload":{"url":"http://a.onion","chunk_index":2}},
              {"score":0.80,"payload":{"url":"http://b.onion","chunk_index":0}}
            ]}""";

        List<SemanticSearchHit> hits = store.search(new float[]{0.1f, 0.2f, 0.3f}, 2);

        assertThat(lastRequestMethod.get()).isEqualTo("POST");
        assertThat(lastRequestPath.get()).isEqualTo("/collections/page_chunks/points/search");
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).url()).isEqualTo("http://a.onion");
        assertThat(hits.get(0).chunkIndex()).isEqualTo(2);
        assertThat(hits.get(0).score()).isEqualTo(0.91);
    }

    @Test
    void hasUnchangedEmbeddingReturnsTrueWhenPointsFound() {
        nextResponseBody = """
            {"result":{"points":[{"id":"abc"}]}}""";

        boolean unchanged = store.hasUnchangedEmbedding("http://x.onion", "hash1");

        assertThat(unchanged).isTrue();
        assertThat(lastRequestPath.get()).isEqualTo("/collections/page_chunks/points/scroll");
    }

    @Test
    void hasUnchangedEmbeddingReturnsFalseWhenNoPointsFound() {
        nextResponseBody = """
            {"result":{"points":[]}}""";

        boolean unchanged = store.hasUnchangedEmbedding("http://x.onion", "hash1");

        assertThat(unchanged).isFalse();
    }

    @Test
    void errorResponseThrowsQdrantException() {
        nextResponseStatus = 500;
        nextResponseBody = "{\"status\":\"error\"}";

        assertThatThrownBy(() -> store.search(new float[]{0.1f}, 1))
            .isInstanceOf(QdrantException.class)
            .hasMessageContaining("500");
    }
}
