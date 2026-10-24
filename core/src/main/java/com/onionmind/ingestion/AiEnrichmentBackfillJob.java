package com.onionmind.ingestion;

import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.AiOutcome;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.Enrichment;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;

/**
 * Enriches pages that were indexed before generation was on, or whose last enrichment
 * failed (fase3-sdd 7.7). Re-runs the same order 10-50 processors as the live pipeline,
 * so the content_hash gate and the cost optimizer apply here too. A complete enrichment
 * publishes {@link PageEnrichedEvent} (Fase 4, ADR-009). A page that fails
 * {@code ai.backfill.max-attempts} times goes {@code failed_permanent} and leaves the queue,
 * like {@code ChangeSummaryJob} does for change summaries.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class AiEnrichmentBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(AiEnrichmentBackfillJob.class);

    private final JdbcTemplate jdbc;
    private final List<AiEnrichingProcessor> processors;
    private final PageRepository repository;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final int maxAttempts;

    public AiEnrichmentBackfillJob(JdbcTemplate jdbc,
                                   List<AiEnrichingProcessor> processors,
                                   PageRepository repository,
                                   ApplicationEventPublisher events,
                                   TransactionTemplate tx,
                                   @Value("${ai.backfill.batch-size:20}") int batchSize,
                                   @Value("${ai.backfill.max-attempts:5}") int maxAttempts) {
        this.jdbc = jdbc;
        this.processors = processors.stream()
            .sorted(Comparator.comparingInt(AiEnrichingProcessor::order))
            .toList();
        this.repository = repository;
        this.events = events;
        this.tx = tx;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${ai.backfill.interval-ms:300000}")
    public void run() {
        List<PendingPage> pending = jdbc.query("""
            SELECT id, url, source_type, extracted_text, content_hash, version, ai_attempts
            FROM pages
            WHERE ai_status IN ('pending', 'failed_transient') AND extracted_text IS NOT NULL
            ORDER BY id
            LIMIT ?
            """, (rs, rowNum) -> new PendingPage(
                rs.getLong("id"), rs.getString("url"), rs.getString("source_type"),
                rs.getString("extracted_text"), rs.getString("content_hash"), rs.getInt("version"),
                rs.getInt("ai_attempts")),
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
                failed = true; // a failed step may still carry partial output (EntityProcessor)
            }
            doc = result.document();
        }

        AiOutcome outcome;
        if (!failed) {
            outcome = AiOutcome.processed(doc.enrichment());
        } else if (page.aiAttempts() + 1 >= maxAttempts) {
            log.warn("AI enrichment of {} failed {} time(s), giving up (failed_permanent)", page.url(), page.aiAttempts() + 1);
            outcome = AiOutcome.permanentFailure(doc.enrichment());
        } else {
            outcome = AiOutcome.partialFailure(doc.enrichment());
        }
        Enrichment enrichment = doc.enrichment();

        // Short transaction around the write only — the LLM calls above stay outside any
        // transaction. The event needs one: @ApplicationModuleListener fires after commit,
        // so a rolled-back write never reaches graph/intelligence.
        tx.executeWithoutResult(status -> {
            if (!repository.updateAiFields(page.url(), page.contentHash(), outcome)) {
                log.info("AI enrichment of {} discarded: content changed while it ran", page.url());
                return;
            }
            if (AiOutcome.PROCESSED.equals(outcome.status())) {
                events.publishEvent(enrichedEvent(page, enrichment));
            }
        });
    }

    private static PageEnrichedEvent enrichedEvent(PendingPage page, Enrichment enrichment) {
        var category = enrichment.category();
        return new PageEnrichedEvent(page.id(), page.url(), PageEnrichedEvent.hostOf(page.url()), page.version(),
            page.contentHash(), category == null ? null : category.category(),
            category == null ? 0.0 : category.confidence(), enrichment.entities());
    }

    private record PendingPage(long id, String url, String sourceType, String extractedText,
                               String contentHash, int version, int aiAttempts) {}
}
