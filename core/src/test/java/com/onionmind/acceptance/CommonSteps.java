package com.onionmind.acceptance;

import com.onionmind.HttpJson;
import com.onionmind.content.EmbeddingBackfillJob;
import com.onionmind.ingestion.AiEnrichmentBackfillJob;
import com.onionmind.search.QdrantVectorStore;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.pt.Dado;
import io.cucumber.java.pt.Então;
import io.cucumber.java.pt.Quando;
import org.neo4j.driver.Driver;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Scenario lifecycle and the steps every phase shares: publish to {@code raw-pages}, run the
 * enrichment cycle, call the search API. Phase step classes inject this one for its state.
 */
public class CommonSteps {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KafkaContainer kafka;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private QdrantVectorStore qdrant;
    @Autowired
    private FakeAiProviders fakes;
    @Autowired
    private EmbeddingBackfillJob embeddingBackfill;
    @Autowired
    private AiEnrichmentBackfillJob aiBackfill;
    @Autowired
    private Driver neo4j;
    @Value("${local.server.port}")
    private int port;
    @Value("${qdrant.url}")
    private String qdrantHost;
    @Value("${qdrant.port}")
    private int qdrantPort;
    @Value("${qdrant.collection}")
    private String collection;

    private KafkaProducer<String, String> producer;
    HttpJson response;

    @Before
    public void resetState() throws Exception {
        jdbc.update("DELETE FROM agent_runs");
        jdbc.update("DELETE FROM agents WHERE name <> 'Research Agent'");
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM page_changes");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM quarantined_pages");
        jdbc.update("DELETE FROM pages");
        try (var session = neo4j.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
        try (HttpClient client = HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder(URI.create(
                    "http://%s:%d/collections/%s".formatted(qdrantHost, qdrantPort, collection))).DELETE().build(),
                HttpResponse.BodyHandlers.discarding());
        }
        qdrant.ensureCollection();
        fakes.reset();
        producer = new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()),
            new StringSerializer(), new StringSerializer());
    }

    @After
    public void closeProducer() {
        producer.close();
    }

    @Dado("que a página {string} foi publicada com o texto {string}")
    @Quando("a página {string} é publicada com o texto {string}")
    public void publishText(String url, String text) {
        publishHtml(url, "<html><body><p>" + text + "</p></body></html>");
    }

    @Quando("a página {string} é publicada com o HTML:")
    public void publishHtml(String url, String html) {
        Timestamp before = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        int quarantinedBefore = quarantinedCount(url);
        String event = MAPPER.writeValueAsString(Map.of(
            "url", url, "source_type", "tor", "html", html, "fetched_at", Instant.now().toString()));
        try {
            producer.send(new ProducerRecord<>("raw-pages", url, event)).get();
        } catch (Exception e) {
            throw new IllegalStateException("publish to raw-pages failed", e);
        }
        // Ingested (inserted, re-versioned or just touched) or quarantined by the guard.
        await().atMost(Duration.ofSeconds(30)).until(() ->
            quarantinedCount(url) > quarantinedBefore
                || jdbc.queryForObject("SELECT count(*) FROM pages WHERE url = ? AND last_seen_at >= ?",
                    Integer.class, url, before) > 0);
    }

    @Dado("que as páginas abaixo foram publicadas:")
    public void publishAll(DataTable table) {
        for (Map<String, String> row : table.asMaps()) {
            publishText(row.get("url"), row.get("texto"));
        }
    }

    @Dado("que o ciclo de enriquecimento rodou")
    @Quando("o ciclo de enriquecimento roda")
    public void runEnrichmentCycle() {
        // Same jobs the scheduler runs in production (design D6), triggered instead of awaited.
        embeddingBackfill.run();
        aiBackfill.run();
        awaitEventsDelivered();
    }

    /** Fase 4 listeners run async after commit; done when no publication is left incomplete. */
    void awaitEventsDelivered() {
        await().atMost(Duration.ofSeconds(30)).until(() ->
            jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class) == 0);
    }

    @Quando("eu busco por {string}")
    public void search(String query) {
        response = HttpJson.get(api("/api/search?q=" + HttpJson.encode(query)));
    }

    @Quando("eu faço a busca semântica por {string}")
    public void semanticSearch(String query) {
        response = HttpJson.get(api("/api/search/semantic?q=" + HttpJson.encode(query)));
    }

    @Quando("eu faço a busca semântica por {string} pedindo {int} resultados")
    public void semanticSearch(String query, int size) {
        response = HttpJson.get(api("/api/search/semantic?q=" + HttpJson.encode(query) + "&size=" + size));
    }

    @Então("a resposta tem status {int}")
    public void assertStatus(int status) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
    }

    @Então("os resultados contêm a página {string}")
    public void assertContains(String url) {
        assertThat(resultUrls()).contains(url);
    }

    @Então("os resultados não contêm a página {string}")
    public void assertNotContains(String url) {
        assertThat(resultUrls()).doesNotContain(url);
    }

    String api(String path) {
        return "http://localhost:" + port + path;
    }

    List<JsonNode> results() {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        List<JsonNode> out = new ArrayList<>();
        response.json().path("results").forEach(out::add);
        return out;
    }

    List<String> resultUrls() {
        return results().stream().map(r -> r.path("url").asString()).toList();
    }

    JsonNode resultFor(String url) {
        return results().stream().filter(r -> url.equals(r.path("url").asString())).findFirst()
            .orElseThrow(() -> new AssertionError("no result for " + url + " in " + response.body()));
    }

    private int quarantinedCount(String url) {
        return jdbc.queryForObject("SELECT count(*) FROM quarantined_pages WHERE url = ?", Integer.class, url);
    }
}
