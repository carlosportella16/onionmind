package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.AllProvidersExhaustedException;
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
 * with whatever the earlier steps produced (fase3-sdd 7.6). No provider with quota is not a
 * failure: the step returns WAITING and the backfill leaves the page for its next run. The
 * text handed to {@link #enrich} is capped at {@code maxInputChars}, so a 2 MB page never
 * reaches the LLM whole (fix-real-infra-findings D1); {@code document.extractedText()} still
 * has all of it.
 */
abstract class AbstractAiProcessor implements AiEnrichingProcessor {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final AIOrchestrator orchestrator;
    private final AiEnrichmentGate gate;
    private final int maxInputChars;

    protected AbstractAiProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate, int maxInputChars) {
        this.orchestrator = orchestrator;
        this.gate = gate;
        this.maxInputChars = maxInputChars;
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
            return ProcessingResult.success(enrich(document, cap(text)));
        } catch (RuntimeException e) {
            String error = getClass().getSimpleName() + ": " + e.getMessage();
            if (AllProvidersExhaustedException.isQuotaOnly(e)) {
                // no provider had quota: a state to wait out, not a failure — nothing partial is kept
                return ProcessingResult.waiting(document, error);
            }
            boolean partial = e instanceof PartialFailure;
            log.warn("{} {} for {}: {}", getClass().getSimpleName(), partial ? "partially failed" : "failed",
                document.url(), e.getMessage());
            return ProcessingResult.failed(partial ? ((PartialFailure) e).partial : document, error);
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

    private String cap(String text) {
        return text.length() > maxInputChars ? text.substring(0, maxInputChars) : text;
    }

    protected abstract Document enrich(Document document, String text);

    /**
     * Thrown by {@link #enrich} when part of the work succeeded: the step still FAILS (so the
     * backfill retries it), but carries the partial document instead of dropping it.
     */
    protected static final class PartialFailure extends RuntimeException {
        private final transient Document partial;

        PartialFailure(Document partial, RuntimeException cause) {
            super(cause.getMessage(), cause);
            this.partial = partial;
        }
    }
}
