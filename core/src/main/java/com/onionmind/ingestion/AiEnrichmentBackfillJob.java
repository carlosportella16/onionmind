package com.onionmind.ingestion;

import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.AiOutcome;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Enriches pages that were indexed before generation was on, or whose last enrichment
 * failed (fase3-sdd 7.7). Re-runs the same order 10-40 processors as the live pipeline,
 * so the content_hash gate and the cost optimizer apply here too.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class AiEnrichmentBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(AiEnrichmentBackfillJob.class);

    private final JdbcTemplate jdbc;
    private final List<AiEnrichingProcessor> processors;
    private final PageRepository repository;
    private final int batchSize;

    public AiEnrichmentBackfillJob(JdbcTemplate jdbc,
                                   List<AiEnrichingProcessor> processors,
                                   PageRepository repository,
                                   @Value("${ai.backfill.batch-size:20}") int batchSize) {
        this.jdbc = jdbc;
        this.processors = processors.stream()
            .sorted(Comparator.comparingInt(AiEnrichingProcessor::order))
            .toList();
        this.repository = repository;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ai.backfill.interval-ms:300000}")
    public void run() {
        List<PendingPage> pending = jdbc.query("""
            SELECT url, source_type, extracted_text
            FROM pages
            WHERE ai_status IN ('pending', 'failed_transient') AND extracted_text IS NOT NULL
            ORDER BY id
            LIMIT ?
            """, (rs, rowNum) -> new PendingPage(
                rs.getString("url"), rs.getString("source_type"), rs.getString("extracted_text")),
            batchSize);

        if (pending.isEmpty()) {
            return;
        }
        log.info("AI enrichment backfill: processing {} page(s)", pending.size());
        for (PendingPage page : pending) {
            enrichOne(page);
        }
    }

    private void enrichOne(PendingPage page) {
        Document doc = new Document(page.url(), page.sourceType(), null, page.extractedText(), DocumentType.HTML);
        boolean failed = false;

        for (AiEnrichingProcessor processor : processors) {
            if (!processor.supports(doc.type())) continue;
            ProcessingResult result = processor.process(doc);
            if (result.status() == ProcessingResult.Status.FAILED) {
                failed = true;
                continue;
            }
            doc = result.document();
        }

        AiOutcome outcome = failed
            ? AiOutcome.partialFailure(doc.enrichment())
            : AiOutcome.processed(doc.enrichment());
        repository.updateAiFields(page.url(), outcome);
    }

    private record PendingPage(String url, String sourceType, String extractedText) {}
}
