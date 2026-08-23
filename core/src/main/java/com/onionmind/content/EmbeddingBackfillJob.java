package com.onionmind.content;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Backfills pages indexed before EmbeddingProcessor existed, or whose last embedding attempt
 * failed (master-sdd sec. 8.2 — "consistência eventual observável"). Reuses EmbeddingProcessor
 * directly instead of duplicating chunk/embed/upsert logic; extracted_text is re-read from
 * Postgres, which stays the source of truth — Qdrant only ever holds the vectors.
 */
@Component
@ConditionalOnProperty(prefix = "embedding", name = "enabled", havingValue = "true")
public class EmbeddingBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillJob.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingProcessor embeddingProcessor;
    private final int batchSize;

    public EmbeddingBackfillJob(JdbcTemplate jdbc,
                                 EmbeddingProcessor embeddingProcessor,
                                 @Value("${embedding.batch-size}") int batchSize) {
        this.jdbc = jdbc;
        this.embeddingProcessor = embeddingProcessor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${embedding.backfill-interval-ms:300000}")
    public void run() {
        List<PendingPage> pending = jdbc.query("""
            SELECT url, source_type, extracted_text
            FROM pages
            WHERE embedding_status IN (?, ?) AND extracted_text IS NOT NULL
            ORDER BY id
            LIMIT ?
            """, (rs, rowNum) -> new PendingPage(
                rs.getString("url"), rs.getString("source_type"), rs.getString("extracted_text")
            ), "pending", EmbeddingOutcome.FAILED_TRANSIENT, batchSize);

        if (pending.isEmpty()) {
            return;
        }

        log.info("Embedding backfill: processing {} page(s)", pending.size());
        for (PendingPage page : pending) {
            embedOne(page);
        }
    }

    private void embedOne(PendingPage page) {
        var doc = new Document(page.url(), page.sourceType(), null, page.extractedText(), DocumentType.HTML);
        var result = embeddingProcessor.process(doc);
        var outcome = EmbeddingOutcome.from(result);
        if (outcome == null) {
            return;
        }
        jdbc.update("""
            UPDATE pages
            SET embedding_status = ?, embedding_attempted_at = now(), embedding_error_message = ?
            WHERE url = ?
            """, outcome.status(), outcome.errorMessage(), page.url());
    }

    private record PendingPage(String url, String sourceType, String extractedText) {}
}
