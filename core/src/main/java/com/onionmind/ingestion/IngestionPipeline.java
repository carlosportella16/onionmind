package com.onionmind.ingestion;

import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.ContentProcessor;
import com.onionmind.content.EmbeddingProcessor;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * AI enrichment and embedding never run on this path (fix-ingestion-stability): a slow or
 * hanging provider call must never be able to block the Kafka listener thread long enough
 * to exceed max.poll.interval.ms and evict the consumer from its group. AiEnrichmentBackfillJob
 * and EmbeddingBackfillJob already know how to run those same processors against a `pending`
 * page on a schedule — this pipeline only does what's fast: sanitize, extract, guard, persist,
 * leaving ai_status/embedding_status at 'pending' for the backfill jobs to pick up.
 */
@Component
public class IngestionPipeline {
    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private final List<ContentProcessor> processors;
    private final PageRepository repository;

    public IngestionPipeline(List<ContentProcessor> processors, PageRepository repository) {
        this.processors = processors.stream()
            .filter(p -> !(p instanceof AiEnrichingProcessor) && !(p instanceof EmbeddingProcessor))
            .sorted(Comparator.comparingInt(ContentProcessor::order))
            .toList();
        this.repository = repository;
    }

    @Transactional
    public void process(RawPageEvent event) {
        var doc = event.toDocument();

        for (var processor : processors) {
            if (!processor.supports(doc.type())) continue;

            var result = processor.process(doc);
            if (result.status() == ProcessingResult.Status.HALT) {
                log.warn("Ingestion halted for {} by {}: {}",
                    doc.url(), processor.getClass().getSimpleName(), result.error());
                repository.quarantine(doc, result.error());
                return; // no further processing, no content persisted
            }
            if (result.status() == ProcessingResult.Status.FAILED) {
                log.warn("Processor {} failed for {}: {}",
                    processor.getClass().getSimpleName(), doc.url(), result.error());
                continue; // does not block the rest of the pipeline
            }
            doc = result.document();
        }

        if (doc.extractedText() == null || doc.extractedText().isBlank()) {
            log.info("Skipping {} — no extractable text", doc.url());
            return;
        }

        repository.upsertWithVersioning(doc, null);
    }
}
