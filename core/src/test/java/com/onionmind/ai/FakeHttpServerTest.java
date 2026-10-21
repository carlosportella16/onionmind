package com.onionmind.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class FakeHttpServerTest {

    @Test
    void responderAnswersFromPathAndBody() throws Exception {
        try (var server = new FakeHttpServer(); var client = HttpClient.newHttpClient()) {
            server.responder = (path, body) -> new FakeHttpServer.Reply(201, path + "|" + body);

            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/api/x"))
                    .POST(HttpRequest.BodyPublishers.ofString("ping"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).isEqualTo("/api/x|ping");
            assertThat(server.requestBodies).containsExactly("ping");
        }
    }
}
