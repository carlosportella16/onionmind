package com.onionmind.ai.provider;

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
 * Shared HTTP+JSON plumbing for the cloud/local adapters. Mirrors the raw
 * {@code java.net.http.HttpClient} + Jackson style already used by OllamaAIOrchestrator
 * (Fase 2) rather than pulling in a new client.
 */
abstract class HttpAIProvider implements AIProvider {

    protected final ObjectMapper mapper = JsonMapper.builder().build();
    protected final Duration requestTimeout;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    protected HttpAIProvider(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /** POSTs {@code body} as JSON, returns the parsed response tree, maps failures to {@link ProviderException}. */
    protected JsonNode postJson(String url, Map<String, String> headers, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json");
        headers.forEach(request::header);

        HttpResponse<String> response;
        try {
            String json = mapper.writeValueAsString(body);
            response = httpClient.send(
                request.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new ProviderException(id() + " request failed", e);
        }

        int status = response.statusCode();
        if (status >= 400) {
            boolean rateLimited = status == 429;
            boolean retryable = rateLimited || status == 408 || status >= 500;
            throw new ProviderException(
                id() + " HTTP " + status + " - " + truncate(response.body()), retryable, rateLimited);
        }

        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new ProviderException(id() + " returned unparseable body", e);
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }
}
