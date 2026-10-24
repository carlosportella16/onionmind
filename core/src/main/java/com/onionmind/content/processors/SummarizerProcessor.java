package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * order 30 — a 2-3 sentence summary in {@code pages.summary}, always from the original
 * text, whatever its language.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class SummarizerProcessor extends AbstractAiProcessor {

    public SummarizerProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate,
                               @Value("${ai.max-input-chars:8000}") int maxInputChars) {
        super(orchestrator, gate, maxInputChars);
    }

    @Override
    protected Document enrich(Document document, String text) {
        Summary summary = orchestrator.summarize(text,
            TaskContext.batch(TaskContext.TaskType.SUMMARIZE, approxTokens(text), "en", false));
        return document.withEnrichment(document.enrichment().withSummary(summary));
    }

    @Override
    public int order() {
        return 30;
    }
}
