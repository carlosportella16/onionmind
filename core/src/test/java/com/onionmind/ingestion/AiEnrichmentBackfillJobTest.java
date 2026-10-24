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

    private static final int MAX_ATTEMPTS = 3;

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
        return new AiEnrichmentBackfillJob(jdbc, List.of(processor), repository, published::add, tx, 10, MAX_ATTEMPTS);
    }

    private AiEnrichmentBackfillJob job(AiEnrichingProcessor processor) {
        return job(processor, new PageRepository(jdbc));
    }

    private long insertPage(String url, String status) {
        return insertPage(url, status, 0);
    }

    private long insertPage(String url, String status, int attempts) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status, ai_attempts)
            VALUES (?, 'tor', 'some page body text', 'hash-1', 3, ?, ?)
            """, url, status, attempts);
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
    void failureBelowMaxAttemptsStaysTransient() {
        insertPage("http://below.onion", "failed_transient", MAX_ATTEMPTS - 2);

        job(new FakeEnricher(true)).run();

        assertThat(jdbc.queryForMap("SELECT ai_status, ai_attempts FROM pages WHERE url = ?", "http://below.onion"))
            .containsEntry("ai_status", "failed_transient").containsEntry("ai_attempts", MAX_ATTEMPTS - 1);
    }

    @Test
    void failureAtMaxAttemptsGoesPermanentAndLeavesTheQueue() {
        insertPage("http://hopeless.onion", "failed_transient", MAX_ATTEMPTS - 1);
        FakeEnricher enricher = new FakeEnricher(true);

        job(enricher).run();

        var row = jdbc.queryForMap(
            "SELECT ai_status, ai_attempts, ai_error_message, entities->0->>'key' AS e FROM pages WHERE url = ?",
            "http://hopeless.onion");
        assertThat(row).containsEntry("ai_status", "failed_permanent").containsEntry("ai_attempts", MAX_ATTEMPTS)
            .containsEntry("e", WALLET.key()); // keeps what the steps produced
        assertThat((String) row.get("ai_error_message")).contains("max attempts");
        assertThat(published).isEmpty();

        job(enricher).run();
        assertThat(enricher.calls).isEqualTo(1); // not selected again
    }

    @Test
    void withoutQuotaThePageStaysQueuedWithItsAttemptsUntouched() {
        insertPage("http://no-quota.onion", "failed_transient", MAX_ATTEMPTS - 1);
        WaitingEnricher enricher = new WaitingEnricher();

        job(enricher).run();
        job(enricher).run(); // however many runs: waiting never reaches failed_permanent

        assertThat(jdbc.queryForMap("SELECT ai_status, ai_attempts FROM pages WHERE url = ?", "http://no-quota.onion"))
            .containsEntry("ai_status", "failed_transient").containsEntry("ai_attempts", MAX_ATTEMPTS - 1);
        assertThat(published).isEmpty();
    }

    @Test
    void withoutQuotaTheRestOfTheBatchIsNotTried() {
        insertPage("http://first.onion", "pending");
        insertPage("http://second.onion", "pending");
        WaitingEnricher enricher = new WaitingEnricher();

        job(enricher).run();

        assertThat(enricher.calls).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT ai_status FROM pages", String.class)).containsOnly("pending");
    }

    @Test
    void successAfterPreviousFailuresIsProcessedAndPublished() {
        insertPage("http://recovered.onion", "failed_transient", MAX_ATTEMPTS - 1);

        job(new FakeEnricher(false)).run();

        assertThat(jdbc.queryForObject("SELECT ai_status FROM pages WHERE url = ?", String.class,
            "http://recovered.onion")).isEqualTo("processed");
        assertThat(published).hasSize(1);
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

    /** What AbstractAiProcessor returns when the router found no provider with quota. */
    private static class WaitingEnricher extends FakeEnricher {
        WaitingEnricher() {
            super(false);
        }

        @Override
        public ProcessingResult process(Document document) {
            calls++;
            return ProcessingResult.waiting(document, "SummarizerProcessor: no AI provider with quota");
        }
    }

    private static class FakeEnricher implements AiEnrichingProcessor {
        private final boolean namedEntitiesFail;
        int calls;

        FakeEnricher(boolean namedEntitiesFail) {
            this.namedEntitiesFail = namedEntitiesFail;
        }

        @Override
        public ProcessingResult process(Document document) {
            calls++;
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
