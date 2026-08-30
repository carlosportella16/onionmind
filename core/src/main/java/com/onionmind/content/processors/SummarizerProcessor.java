package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.Translation;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * order 30 — a 2-3 sentence summary in {@code pages.summary}. Summarizes the Portuguese
 * translation when the order 20 step produced one, otherwise the original text.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class SummarizerProcessor extends AbstractAiProcessor {

    public SummarizerProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate) {
        super(orchestrator, gate);
    }

    @Override
    protected Document enrich(Document document, String text) {
        Translation translation = document.enrichment().translation();
        String source = translation != null && translation.text() != null && !translation.text().isBlank()
            ? translation.text()
            : text;

        Summary summary = orchestrator.summarize(source,
            TaskContext.batch(TaskContext.TaskType.SUMMARIZE, approxTokens(source), "pt", false));
        return document.withEnrichment(document.enrichment().withSummary(summary));
    }

    @Override
    public int order() {
        return 30;
    }
}
