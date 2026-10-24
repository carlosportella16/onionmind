package com.onionmind.ingestion;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Summary;
import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.AiOutcome;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AiEnrichmentBackfillJobTest {

    private static final ExtractedEntity WALLET =
        ExtractedEntity.of(EntityType.CRYPTO_WALLET, "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", 1.0);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private final List<Object> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM page_changes");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
        published.clear();
    }

    private AiEnrichmentBackfillJob job(AiEnrichingProcessor processor, PageRepository repository) {
        return new AiEnrichmentBackfillJob(jdbc, List.of(processor), repository, published::add, tx, 10);
    }

    private AiEnrichmentBackfillJob job(AiEnrichingProcessor processor) {
        return job(processor, new PageRepository(jdbc));
    }

    private long insertPage(String url, String status) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status)
            VALUES (?, 'tor', 'some page body text', 'hash-1', 3, ?)
            """, url, status);
        return jdbc.queryForObject("SELECT id FROM pages WHERE url = ?", Long.class, url);
    }

    @Test
    void enrichesPendingPagesAndPublishesTheEnrichedEventOnce() {
        long id = insertPage("http://pending.onion/a", "pending");

        job(new FakeEnricher(false)).run();

        var row = jdbc.queryForMap(
            "SELECT ai_status, summary->>'text' AS s, entities->0->>'key' AS e FROM pages WHERE url = ?",
            "http://pending.onion/a");
        assertThat(row).containsEntry("ai_status", "processed").containsEntry("s", "resumo backfill")
            .containsEntry("e", WALLET.key());
        assertThat(published).containsExactly(new PageEnrichedEvent(id, "http://pending.onion/a", "pending.onion",
            3, "hash-1", "forum", 0.8, List.of(WALLET)));
    }

    @Test
    void retriesTransientFailures() {
        insertPage("http://failed.onion", "failed_transient");

        job(new FakeEnricher(false)).run();

        assertThat(jdbc.queryForObject(
            "SELECT ai_status FROM pages WHERE url = ?", String.class, "http://failed.onion"))
            .isEqualTo("processed");
    }

    @Test
    void partialFailureKeepsPartialOutputButPublishesNothing() {
        insertPage("http://partial.onion", "pending");

        job(new FakeEnricher(true)).run();

        var row = jdbc.queryForMap(
            "SELECT ai_status, entities->0->>'key' AS e FROM pages WHERE url = ?", "http://partial.onion");
        assertThat(row).containsEntry("ai_status", "failed_transient").containsEntry("e", WALLET.key());
        assertThat(published).isEmpty();
    }

    @Test
    void contentChangedWhileEnrichingDiscardsTheResultAndPublishesNothing() {
        insertPage("http://moving.onion", "pending");
        AiEnrichingProcessor recrawlMidway = new FakeEnricher(false) {
            @Override
            public ProcessingResult process(Document document) {
                jdbc.update("UPDATE pages SET content_hash = 'hash-2', version = 4 WHERE url = ?", document.url());
                return super.process(document);
            }
        };

        job(recrawlMidway).run();

        assertThat(jdbc.queryForObject("SELECT ai_status FROM pages WHERE url = ?", String.class,
            "http://moving.onion")).isEqualTo("pending");
        assertThat(published).isEmpty();
    }

    @Test
    void failingWriteRollsBackAndPublishesNothing() {
        insertPage("http://broken.onion", "pending");
        PageRepository failing = new PageRepository(jdbc) {
            @Override
            public boolean updateAiFields(String url, String contentHash, AiOutcome outcome) {
                throw new IllegalStateException("db down");
            }
        };

        assertThatThrownBy(() -> job(new FakeEnricher(false), failing).run()).isInstanceOf(IllegalStateException.class);
        assertThat(published).isEmpty();
    }

    @Test
    void leavesAlreadyProcessedPagesAlone() {
        insertPage("http://done.onion", "processed");

        job(new FakeEnricher(false)).run();

        var row = jdbc.queryForMap(
            "SELECT ai_status, summary FROM pages WHERE url = ?", "http://done.onion");
        assertThat(row).containsEntry("ai_status", "processed");
        assertThat(row.get("summary")).isNull(); // never touched
        assertThat(published).isEmpty();
    }

    private static class FakeEnricher implements AiEnrichingProcessor {
        private final boolean namedEntitiesFail;

        FakeEnricher(boolean namedEntitiesFail) {
            this.namedEntitiesFail = namedEntitiesFail;
        }

        @Override
        public ProcessingResult process(Document document) {
            Document enriched = document.withEnrichment(document.enrichment()
                .withSummary(new Summary("resumo backfill", 0.9))
                .withCategory(new Classification("forum", 0.8))
                .withEntities(List.of(WALLET)));
            return namedEntitiesFail
                ? ProcessingResult.failed(enriched, "EntityProcessor: all providers failed")
                : ProcessingResult.success(enriched);
        }

        @Override
        public boolean supports(DocumentType type) {
            return type == DocumentType.HTML;
        }

        @Override
        public int order() {
            return 30;
        }
    }
}
