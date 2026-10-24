package com.onionmind.intelligence;

import com.onionmind.Fase4IntegrationTestBase;
import com.onionmind.ingestion.AiEnrichmentBackfillJob;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Fase 4 exit gate (sdd sec. 5/14): "what changed in this category since yesterday" returns a
 * correct, short answer. Full cycle: raw pages on Kafka -> ingestion -> AI backfill ->
 * PageEnrichedEvent -> diff -> summary job -> /api/changes. Fake LLM, everything else real.
 */
class Fase4ChangeQueryGateTest extends Fase4IntegrationTestBase {

    @Autowired
    private KafkaContainer kafka;
    @Autowired
    private AiEnrichmentBackfillJob backfill;
    @Autowired
    private ChangeSummaryJob summaries;
    @Autowired
    private ChangesController changes;

    private void crawl(String url, String text) throws Exception {
        try (var producer = new KafkaProducer<>(
            Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()),
            new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("raw-pages", url, """
                {"url":"%s","source_type":"tor","html":"<html><body><p>%s</p></body></html>","fetched_at":"%s"}
                """.formatted(url, text, Instant.now()))).get();
        }
    }

    private void awaitVersionPending(String url, int version) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.queryForMap(
            "SELECT version, ai_status FROM pages WHERE url = ?", url))
            .containsEntry("version", version).containsEntry("ai_status", "pending"));
    }

    @Test
    void whatChangedInForumsSinceYesterdayIsCorrectAndShort() throws Exception {
        String forum = "http://forum-gate.onion/";
        String counterOnly = "http://forum-contador.onion/";
        String market = "http://mercado-gate.onion/";

        crawl(forum, "Bem-vindo ao fórum de privacidade. Regras da casa: sem spam.");
        crawl(counterOnly, "Fórum de leitores. Visitas hoje: 1523.");
        crawl(market, "Mercado de livros raros. Entrega discreta.");
        for (String url : new String[]{forum, counterOnly, market}) awaitVersionPending(url, 1);
        backfill.run();

        crawl(forum, "Bem-vindo ao fórum de privacidade. Regras da casa: sem spam. Novo tópico: guia completo de carteiras Monero para iniciantes.");
        crawl(counterOnly, "Fórum de leitores. Visitas hoje: 1524.");
        crawl(market, "Mercado de livros raros. Entrega discreta. Agora aceitamos pagamento em 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa.");
        for (String url : new String[]{forum, counterOnly, market}) awaitVersionPending(url, 2);
        backfill.run();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
            jdbc.queryForObject("SELECT count(*) FROM page_changes", Integer.class)).isEqualTo(3));
        summaries.run();

        var body = changes.changes(Instant.now().minus(Duration.ofDays(1)).toString(), "forum", 20).getBody();

        assertThat(body.total()).isEqualTo(1);
        assertThat(body.changes()).singleElement().satisfies(c -> {
            assertThat(c.url()).isEqualTo(forum);
            assertThat(c.fromVersion()).isEqualTo(1);
            assertThat(c.toVersion()).isEqualTo(2);
            assertThat(c.summary().text()).isEqualTo("Change: Novo tópico: guia completo de carteiras Monero para iniciantes.");
        });
        var marketplace = changes.changes(null, "marketplace", 20).getBody();
        assertThat(marketplace.changes()).singleElement().satisfies(c ->
            assertThat(c.entitiesAdded()).extracting(e -> e.get("type")).containsExactly("crypto_wallet"));
    }
}
