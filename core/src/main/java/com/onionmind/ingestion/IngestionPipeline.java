package com.onionmind.ingestion;

import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.AiOutcome;
import com.onionmind.content.ContentProcessor;
import com.onionmind.content.EmbeddingOutcome;
import com.onionmind.content.EmbeddingProcessor;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import com.onionmind.ingestion.internal.UpsertOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Component
public class IngestionPipeline {
    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private final List<ContentProcessor> processors;
    private final PageRepository repository;
    private final ApplicationEventPublisher events;

    public IngestionPipeline(List<ContentProcessor> processors, PageRepository repository,
                              ApplicationEventPublisher events) {
        this.processors = processors.stream()
            .sorted(Comparator.comparingInt(ContentProcessor::order))
            .toList();
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    public void process(RawPageEvent event) {
        var doc = event.toDocument();
        EmbeddingOutcome embeddingOutcome = null;
        boolean sawAi = false;
        boolean aiFailed = false;
        boolean aiUnchanged = false;

        for (var processor : processors) {
            if (!processor.supports(doc.type())) continue;

            var result = processor.process(doc);
            if (result.status() == ProcessingResult.Status.HALT) {
                log.warn("Ingestion halted for {} by {}: {}",
                    doc.url(), processor.getClass().getSimpleName(), result.error());
                repository.quarantine(doc, result.error());
                return; // no further processing, no content persisted
            }
            if (processor instanceof EmbeddingProcessor) {
                embeddingOutcome = EmbeddingOutcome.from(result);
            }
            if (processor instanceof AiEnrichingProcessor) {
                sawAi = true;
                if (result.status() == ProcessingResult.Status.FAILED) {
                    aiFailed = true;
                } else if (result.status() == ProcessingResult.Status.SKIPPED && result.error() == null) {
                    aiUnchanged = true; // content_hash gate said the page is unchanged
                }
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

        UpsertOutcome upsert = repository.upsertWithVersioning(doc, embeddingOutcome);

        if (sawAi && !aiUnchanged) {
            AiOutcome outcome = aiFailed
                ? AiOutcome.partialFailure(doc.enrichment())
                : AiOutcome.processed(doc.enrichment());
            repository.updateAiFields(doc.url(), outcome);
        }

        // ADR-009: ingestion never knows who reacts — graph/intelligence (Phase 4) do.
        events.publishEvent(new PageIndexedEvent(
            upsert.pageId(), doc.url(), doc.resolvedContentHash(), upsert.version(), upsert.isNewVersion()));
    }
}
