package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.content.AiEnrichingProcessor;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import com.onionmind.content.DocumentType;
import com.onionmind.content.ProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared shape for the order 10-40 AI processors: HTML only, skip when there is no text,
 * honour the content_hash gate (don't spend quota on unchanged pages), and never let an
 * AI failure abort the pipeline — a failed step returns FAILED and the page is persisted
 * with whatever the earlier steps produced (fase3-sdd 7.6).
 */
abstract class AbstractAiProcessor implements AiEnrichingProcessor {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final AIOrchestrator orchestrator;
    private final AiEnrichmentGate gate;

    protected AbstractAiProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate) {
        this.orchestrator = orchestrator;
        this.gate = gate;
    }

    @Override
    public final ProcessingResult process(Document document) {
        String text = document.extractedText();
        if (text == null || text.isBlank()) {
            return ProcessingResult.skipped(document, "no extracted text");
        }
        if (gate.alreadyEnriched(document)) {
            return ProcessingResult.unchanged(document);
        }
        try {
            return ProcessingResult.success(enrich(document, text));
        } catch (RuntimeException e) {
            log.warn("{} failed for {}: {}", getClass().getSimpleName(), document.url(), e.getMessage());
            return ProcessingResult.failed(document, getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public boolean supports(DocumentType type) {
        return type == DocumentType.HTML;
    }

    /** Approximate token count — the router uses it to pick a provider. */
    protected static int approxTokens(String text) {
        return text.length() / 4;
    }

    protected abstract Document enrich(Document document, String text);
}
