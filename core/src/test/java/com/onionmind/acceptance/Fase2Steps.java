package com.onionmind.acceptance;

import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Então;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class Fase2Steps {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private CommonSteps common;
    @Autowired
    private JdbcTemplate jdbc;
    @Value("${qdrant.url}")
    private String qdrantHost;
    @Value("${qdrant.port}")
    private int qdrantPort;
    @Value("${qdrant.collection}")
    private String collection;

    /** The full-text half alone — /api/search fuses in semantic hits, so check the index directly. */
    @Então("a busca full-text por {string} não encontra a página {string}")
    public void assertFullTextMisses(String query, String url) {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM pages WHERE url = ? AND search_vector @@ websearch_to_tsquery('simple', ?)
            """, Integer.class, url, query)).isZero();
    }

    @Então("os resultados vêm vazios")
    public void assertEmpty() {
        assertThat(common.results()).isEmpty();
    }

    @Dado("que a página {string} tem mais de {int} trechos no índice vetorial")
    public void assertChunkCount(String url, int min) throws Exception {
        String body = MAPPER.writeValueAsString(Map.of("exact", true,
            "filter", Map.of("must", List.of(Map.of("key", "url", "match", Map.of("value", url))))));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(
                    "http://%s:%d/collections/%s/points/count".formatted(qdrantHost, qdrantPort, collection)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(MAPPER.readTree(response.body()).path("result").path("count").asInt()).isGreaterThan(min);
        }
    }

    @Então("cada página aparece no máximo uma vez nos resultados")
    public void assertNoDuplicates() {
        List<String> urls = common.resultUrls();
        assertThat(urls).doesNotHaveDuplicates();
    }
}
