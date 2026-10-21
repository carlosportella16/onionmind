package com.onionmind.ai;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * Minimal JDK HttpServer stub for provider adapter tests — same approach as the existing
 * OllamaAIOrchestratorTest, factored out so the three adapter tests share it.
 */
public final class FakeHttpServer implements AutoCloseable {

    public record Reply(int status, String body) {
    }

    private final HttpServer server;
    public volatile int status = 200;
    public volatile String responseBody = "{}";
    /** When set, answers each request from {@code (path, requestBody)} instead of status/responseBody. */
    public volatile BiFunction<String, String, Reply> responder;
    public final List<String> requestBodies = new CopyOnWriteArrayList<>();
    public final List<String> requestPaths = new CopyOnWriteArrayList<>();
    public final List<String> authHeaders = new CopyOnWriteArrayList<>();

    public FakeHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestPaths.add(path);
            requestBodies.add(body);
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authHeaders.add(auth != null ? auth : exchange.getRequestHeaders().getFirst("x-goog-api-key"));

            var current = responder;
            Reply reply = current != null ? current.apply(path, body) : new Reply(status, responseBody);
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
