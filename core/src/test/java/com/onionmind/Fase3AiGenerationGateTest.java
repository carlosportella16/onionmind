package com.onionmind;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Embedding;
import com.onionmind.ai.LanguageDetection;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.Translation;
import com.onionmind.ai.decorator.CacheDecorator;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import com.onionmind.ingestion.AiEnrichmentBackfillJob;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Fase 3 exit gate (fase3-sdd sec. 12): a new page is automatically summarized and
 * classified, with an English summary, within minutes, the provider chosen without any
 * manual step. Enrichment never translates the page (remove-page-translation). Uses a fake AIProvider surface (the @Primary AIOrchestrator) for CI
 * determinism — same approach as Fase2SemanticSearchGateTest. Redis-backed beans are
 * mocked so the test needs only Postgres + Kafka.
 */
@Import({TestcontainersConfiguration.class, Fase3AiGenerationGateTest.FakeAi.class})
@SpringBootTest(properties = "ai.enabled=true")
class Fase3AiGenerationGateTest {

    @Autowired
    private KafkaContainer kafkaContainer;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiEnrichmentBackfillJob backfillJob;

    @MockitoBean
    private ProviderQuotaTracker quotaTracker;
    @MockitoBean
    private CacheDecorator cacheDecorator;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() {
        FakeAi.translateCalls.set(0);
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
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

    private Map<String, Object> awaitEnriched(String url) {
        // fix-ingestion-stability: AI enrichment no longer runs inline on the Kafka listener
        // thread — it only runs on AiEnrichmentBackfillJob's schedule (every ai.backfill.interval-ms
        // in production). The gate itself still holds: a page reaches ai_status='pending' fast,
        // and gets enriched next time the backfill job runs — triggered directly here instead of
        // waiting out its real interval.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT ai_status FROM pages WHERE url = ?", String.class, url))
                .isEqualTo("pending"));
        backfillJob.run();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
            assertThat(jdbc.queryForObject("SELECT ai_status FROM pages WHERE url = ?", String.class, url))
                .isEqualTo("processed"));
        return jdbc.queryForMap("""
            SELECT summary->>'text' AS summary, category->>'category' AS category,
                   language->>'code' AS language
            FROM pages WHERE url = ?
            """, url);
    }

    @Test
    void englishPageIsSummarizedAndClassifiedWithoutTranslation() throws Exception {
        String url = "http://en-" + System.nanoTime() + ".onion/";
        publish(url, "<html><body><p>This English marketplace and forum discusses the anonymous "
            + "trading of digital goods and the privacy tools that people use on the network every day.</p></body></html>");

        Map<String, Object> row = awaitEnriched(url);

        assertThat(row.get("summary")).asString().contains("summary");
        assertThat(row).containsEntry("category", "forum");
        assertThat(row).containsEntry("language", "en");
        assertThat(FakeAi.translateCalls).hasValue(0);
    }

    @Test
    void portuguesePageGetsAnEnglishSummaryWithoutTranslation() throws Exception {
        String url = "http://pt-" + System.nanoTime() + ".onion/";
        publish(url, "<html><body><p>Este forum em portugues discute a privacidade e a troca de bens "
            + "digitais entre os usuarios que nao revelam a identidade para mais ninguem, com dicas de "
            + "seguranca para quem usa a rede no dia a dia.</p></body></html>");

        Map<String, Object> row = awaitEnriched(url);

        assertThat(row.get("summary")).asString().contains("summary");
        assertThat(row).containsEntry("category", "forum");
        assertThat(row).containsEntry("language", "pt");
        assertThat(FakeAi.translateCalls).as("no TRANSLATE call during enrichment").hasValue(0);
    }

    @TestConfiguration
    static class FakeAi {

        /** {@code translate} stays on the AI surface (bilingual-search backlog), not in enrichment. */
        static final AtomicInteger translateCalls = new AtomicInteger();

        @Bean
        @Primary
        AIOrchestrator fakeOrchestrator() {
            return new AIOrchestrator() {
                @Override
                public Summary summarize(String text, TaskContext ctx) {
                    return new Summary("Automatic summary of the page.", 0.9);
                }

                @Override
                public Classification classify(String text, TaskContext ctx) {
                    return new Classification("forum", 0.85);
                }

                @Override
                public Translation translate(String text, String targetLang, TaskContext ctx) {
                    translateCalls.incrementAndGet();
                    return new Translation(text, "pt", 0.9);
                }

                @Override
                public LanguageDetection detectLanguage(String text, TaskContext ctx) {
                    return new LanguageDetection("en", 0.8);
                }

                @Override
                public com.onionmind.ai.EntityExtraction extractEntities(String text, TaskContext ctx) {
                    return new com.onionmind.ai.EntityExtraction(java.util.List.of(), 0.9);
                }

                @Override
                public com.onionmind.ai.QueryRewrite rewriteQuery(String question, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public com.onionmind.ai.Answer answer(String question, java.util.List<String> sources, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Embedding embed(String text, TaskContext ctx) {
                    throw new UnsupportedOperationException("not used in this gate");
                }
            };
        }
    }
}
