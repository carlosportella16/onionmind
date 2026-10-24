package com.onionmind;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP helper shared by the acceptance and smoke glue: GET, plus POST for {@code /api/chat},
 * which reads only. {@code json} is null for non-JSON bodies.
 */
public record HttpJson(int status, String body, JsonNode json) {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static HttpJson get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), "GET " + url);
    }

    /** POSTs {@code body} serialized as JSON. */
    public static HttpJson post(String url, Object body) {
        return send(HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body))), "POST " + url);
    }

    private static HttpJson send(HttpRequest.Builder request, String what) {
        try {
            HttpResponse<String> response = CLIENT.send(request.timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
            return new HttpJson(response.statusCode(), response.body(), parse(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException(what + " failed (is the instance up?): " + e, e);
        }
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }
}
