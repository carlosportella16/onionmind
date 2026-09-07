package com.onionmind.ingestion;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.Summary;
import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AiEnrichmentBackfillJobTest {

    @Autowired
    private JdbcTemplate jdbc;

    private AiEnrichmentBackfillJob job;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM page_diffs");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
        job = new AiEnrichmentBackfillJob(jdbc, List.of(new FakeSummarizer()),
            new PageRepository(jdbc), 10);
    }

    private void insertPage(String url, String status) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status)
            VALUES (?, 'tor', 'some page body text', 'hash-1', 1, ?)
            """, url, status);
    }

    @Test
    void enrichesPendingPages() {
        insertPage("http://pending.onion", "pending");

        job.run();

        var row = jdbc.queryForMap(
            "SELECT ai_status, summary->>'text' AS s FROM pages WHERE url = ?", "http://pending.onion");
        assertThat(row).containsEntry("ai_status", "processed").containsEntry("s", "resumo backfill");
    }

    @Test
    void retriesTransientFailures() {
        insertPage("http://failed.onion", "failed_transient");

        job.run();

        assertThat(jdbc.queryForObject(
            "SELECT ai_status FROM pages WHERE url = ?", String.class, "http://failed.onion"))
            .isEqualTo("processed");
    }

    @Test
    void leavesAlreadyProcessedPagesAlone() {
        insertPage("http://done.onion", "processed");

        job.run();

        var row = jdbc.queryForMap(
            "SELECT ai_status, summary FROM pages WHERE url = ?", "http://done.onion");
        assertThat(row).containsEntry("ai_status", "processed");
        assertThat(row.get("summary")).isNull(); // never touched
    }

    private static final class FakeSummarizer implements AiEnrichingProcessor {
        @Override
        public ProcessingResult process(Document document) {
            return ProcessingResult.success(document.withEnrichment(
                document.enrichment().withSummary(new Summary("resumo backfill", 0.9))));
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
