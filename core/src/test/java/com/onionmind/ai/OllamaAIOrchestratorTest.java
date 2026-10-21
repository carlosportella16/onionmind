package com.onionmind.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaAIOrchestratorTest {

    private HttpServer server;
    private OllamaAIOrchestrator orchestrator;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastRequestPath = new AtomicReference<>();
    private volatile String nextResponseBody = "{}";
    private volatile int nextResponseStatus = 200;

    @BeforeEach
    void startFakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
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
        orchestrator = new OllamaAIOrchestrator("http://127.0.0.1:" + port, "nomic-embed-text");
    }

    @AfterEach
    void stopFakeServer() {
        server.stop(0);
    }

    private TaskContext embedContext() {
        return new TaskContext(TaskContext.TaskType.EMBED, 10, null, false, false, null, 0);
    }

    @Test
    void embedCallsOllamaApiEmbedAndParsesVector() {
        nextResponseBody = "{\"embeddings\":[[0.1,0.2,0.3]]}";

        Embedding embedding = orchestrator.embed("hello world", embedContext());

        assertThat(lastRequestPath.get()).isEqualTo("/api/embed");
        assertThat(lastRequestBody.get()).contains("nomic-embed-text").contains("hello world");
        assertThat(embedding.vector()).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(embedding.confidence()).isEqualTo(1.0);
    }

    @Test
    void errorResponseThrowsOllamaException() {
        nextResponseStatus = 500;
        nextResponseBody = "{\"error\":\"model not found\"}";

        assertThatThrownBy(() -> orchestrator.embed("hello", embedContext()))
            .isInstanceOf(OllamaException.class)
            .hasMessageContaining("500");
    }

    @Test
    void generationIsNotSupportedInThisWiring() {
        assertThatThrownBy(() -> orchestrator.summarize("text", embedContext()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> orchestrator.classify("text", embedContext()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> orchestrator.translate("text", "pt", embedContext()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> orchestrator.detectLanguage("text", embedContext()))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
