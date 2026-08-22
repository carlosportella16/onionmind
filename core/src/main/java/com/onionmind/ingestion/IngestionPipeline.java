package com.onionmind.ingestion;

import com.onionmind.content.ContentProcessor;
import com.onionmind.content.ProcessingResult;
import com.onionmind.ingestion.internal.PageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Component
public class IngestionPipeline {
    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private final List<ContentProcessor> processors;
    private final PageRepository repository;

    public IngestionPipeline(List<ContentProcessor> processors, PageRepository repository) {
        this.processors = processors.stream()
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
            if (result.status() == ProcessingResult.Status.FAILED) {
                log.warn("Processor {} failed for {}: {}",
                    processor.getClass().getSimpleName(), doc.url(), result.error());
                continue; // does not block the rest of the pipeline
            }
            doc = result.document();
        }

        if (doc.extractedText() != null && !doc.extractedText().isBlank()) {
            repository.upsertWithVersioning(doc);
        } else {
            log.info("Skipping {} — no extractable text", doc.url());
        }
    }
}
