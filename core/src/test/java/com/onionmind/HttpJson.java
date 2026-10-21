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

/** GET-only HTTP helper shared by the acceptance and smoke glue. {@code json} is null for non-JSON bodies. */
public record HttpJson(int status, String body, JsonNode json) {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static HttpJson get(String url) {
        try {
            HttpResponse<String> response = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return new HttpJson(response.statusCode(), response.body(), parse(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException("GET " + url + " failed (is the instance up?): " + e, e);
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
