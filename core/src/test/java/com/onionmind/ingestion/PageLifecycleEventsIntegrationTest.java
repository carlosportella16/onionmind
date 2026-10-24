package com.onionmind.ingestion;

import com.onionmind.Fase4IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.IncompleteEventPublications;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR-009 end to end inside the core: the backfill publishes PageEnrichedEvent, both
 * module listeners consume it asynchronously after commit, completed publications leave
 * the registry, and a failing listener neither blocks the other nor loses its delivery.
 */
class PageLifecycleEventsIntegrationTest extends Fase4IntegrationTestBase {

    private static final String WALLET = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";

    @Autowired
    private AiEnrichmentBackfillJob backfill;
    @Autowired
    private IncompleteEventPublications incomplete;

    private void pageAtVersion2(String url) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status)
            VALUES (?, 'tor', ?, 'h2', 2, 'pending')
            """, url, "Mercado novo. Pague em " + WALLET + ".");
        long id = jdbc.queryForObject("SELECT id FROM pages WHERE url = ?", Long.class, url);
        jdbc.update("""
            INSERT INTO page_versions (page_id, version, content_hash, extracted_text, category, entities)
            VALUES (?, 1, 'h1', 'Mercado velho.', '{"category":"marketplace","confidence":0.9}', '[]')
            """, id);
    }

    private int publications() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
    }

    @Test
    void bothModulesConsumeTheEventAndTheRegistryEmpties() {
        pageAtVersion2("http://eventos.onion/");

        backfill.run();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(graph.find("crypto_wallet:" + WALLET)).isPresent();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM page_changes WHERE url = 'http://eventos.onion/'",
                Integer.class)).isEqualTo(1);
            assertThat(publications()).isZero();
        });
    }

    @Test
    void failingListenerDoesNotBlockTheOtherAndIsRedelivered() {
        AtomicBoolean neo4jDown = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (neo4jDown.get()) {
                throw new IllegalStateException("neo4j down");
            }
            return inv.callRealMethod();
        }).when(graph).project(any());
        pageAtVersion2("http://falha.onion/");

        backfill.run();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM page_changes WHERE url = 'http://falha.onion/'",
                Integer.class)).as("intelligence still got it").isEqualTo(1);
            assertThat(publications()).as("graph's delivery stays incomplete").isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("SELECT ai_status FROM pages WHERE url = 'http://falha.onion/'", String.class))
            .as("the publisher's write survived").isEqualTo("processed");

        neo4jDown.set(false);
        incomplete.resubmitIncompletePublicationsOlderThan(Duration.ZERO);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(graph.find("crypto_wallet:" + WALLET)).isPresent();
            assertThat(publications()).isZero();
        });
    }
}
