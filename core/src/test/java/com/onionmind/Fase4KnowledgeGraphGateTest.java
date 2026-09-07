package com.onionmind;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Embedding;
import com.onionmind.ai.Entity;
import com.onionmind.ai.LanguageDetection;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.Translation;
import com.onionmind.ai.decorator.CacheDecorator;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import com.onionmind.graph.GraphStore;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Fase 4 exit gate (master-sdd sec. 5): "'o que mudou nesta categoria desde ontem' retorna
 * resposta correta e curta". Fakes the AIOrchestrator/GraphStore boundary — same approach as
 * Fase2SemanticSearchGateTest/Fase3AiGenerationGateTest — to prove the real wiring (event
 * publication, module listeners, persistence, read endpoints) without needing live LLM
 * providers or a real Neo4j instance.
 */
@Import({TestcontainersConfiguration.class, Fase4KnowledgeGraphGateTest.FakePhase4Dependencies.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"ai.enabled=true", "graph.enabled=true", "intelligence.enabled=true"})
@AutoConfigureTestRestTemplate
class Fase4KnowledgeGraphGateTest {

    @Autowired
    private KafkaContainer kafkaContainer;
    @Autowired
    private JdbcTemplate jdbc;
    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;
    @Autowired
    private org.springframework.boot.resttestclient.TestRestTemplate rest;

    @MockitoBean
    private ProviderQuotaTracker quotaTracker;
    @MockitoBean
    private CacheDecorator cacheDecorator;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM alert_rules");
        jdbc.update("DELETE FROM page_diffs");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
        FakePhase4Dependencies.upsertedEntities.clear();
        producer = new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers()),
            new StringSerializer(), new StringSerializer());
    }

    @AfterEach
    void tearDown() {
        producer.close();
    }

    private void publish(String url, String htmlBody) throws Exception {
        String event = """
            {"url":"%s","source_type":"tor","html":"%s","fetched_at":"2026-07-25T10:00:00Z"}
            """.formatted(url, htmlBody);
        producer.send(new ProducerRecord<>("raw-pages", url, event)).get();
    }

    private String api() {
        return "http://localhost:" + port + "/api";
    }

    @Test
    void reCrawlingAChangedPageProducesAQueryableDiffSummary() throws Exception {
        String url = "http://phase4-" + System.nanoTime() + ".onion/";
        publish(url, "<html><body><p>This forum discusses privacy tools for anonymous "
            + "browsing and secure communication between members of the community.</p></body></html>");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT ai_status FROM pages WHERE url = ?", String.class, url))
                .isEqualTo("processed"));

        // Entities from the first crawl already reached the (fake) graph.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(FakePhase4Dependencies.upsertedEntities).isNotEmpty());

        publish(url, "<html><body><p>This forum now discusses privacy tools, secure "
            + "communication, and a new bitcoin payment feature for members.</p></body></html>");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT version FROM pages WHERE url = ?", Integer.class, url))
                .isEqualTo(2));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var response = rest.getForEntity(api() + "/pages/diff?url=" + url,
                com.onionmind.intelligence.PageDiffView.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().text()).isEqualTo("a página passou a mencionar bitcoin");
            assertThat(response.getBody().fromVersion()).isEqualTo(1);
            assertThat(response.getBody().toVersion()).isEqualTo(2);
        });
    }

    @TestConfiguration
    static class FakePhase4Dependencies {

        static final List<Entity> upsertedEntities = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        AIOrchestrator fakeOrchestrator() {
            return new AIOrchestrator() {
                @Override
                public Summary summarize(String text, TaskContext ctx) {
                    return new Summary("resumo automatico da pagina", 0.9);
                }

                @Override
                public Classification classify(String text, TaskContext ctx) {
                    return new Classification("forum", 0.85);
                }

                @Override
                public Translation translate(String text, String targetLang, TaskContext ctx) {
                    return new Translation("traducao para portugues", "en", 0.9);
                }

                @Override
                public LanguageDetection detectLanguage(String text, TaskContext ctx) {
                    return new LanguageDetection("en", 0.8);
                }

                @Override
                public List<Entity> extractEntities(String text, TaskContext ctx) {
                    return List.of(new Entity(Entity.EntityType.TECHNOLOGY, "encryption", 0.8));
                }

                @Override
                public Summary summarizeDiff(String previousText, String currentText, TaskContext ctx) {
                    return new Summary("a página passou a mencionar bitcoin", 0.9);
                }

                @Override
                public Embedding embed(String text, TaskContext ctx) {
                    throw new UnsupportedOperationException("not used in this gate");
                }
            };
        }

        @Bean
        @Primary
        GraphStore fakeGraphStore() {
            return new GraphStore() {
                private final List<String> processed = new ArrayList<>();

                @Override
                public synchronized boolean hasProcessedContent(String url, String contentHash) {
                    return processed.contains(url + "|" + contentHash);
                }

                @Override
                public synchronized void upsertEntities(String pageUrl, String contentHash, List<Entity> entities) {
                    processed.add(pageUrl + "|" + contentHash);
                    upsertedEntities.addAll(entities);
                }

                @Override
                public List<Entity> findEntitiesByPage(String url) {
                    return upsertedEntities;
                }
            };
        }
    }
}
