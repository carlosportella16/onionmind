package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.LanguageDetection;
import com.onionmind.ai.TaskContext;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import com.onionmind.content.Enrichment.LanguageTag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * order 10 — detects the page language. Local stopword heuristic first; only ambiguous
 * or short text escalates to the LLM (fase3-sdd 7.6 / spec content/ai-enrichment).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class LanguageDetectorProcessor extends AbstractAiProcessor {

    public LanguageDetectorProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate) {
        super(orchestrator, gate);
    }

    @Override
    protected Document enrich(Document document, String text) {
        LanguageTag tag = LanguageHeuristic.detect(text).orElseGet(() -> {
            LanguageDetection llm = orchestrator.detectLanguage(text,
                TaskContext.batch(TaskContext.TaskType.DETECT_LANGUAGE, approxTokens(text), null, false));
            return new LanguageTag(normalize(llm.code()), llm.confidence());
        });
        return document.withEnrichment(document.enrichment().withLanguage(tag));
    }

    @Override
    public int order() {
        return 10;
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) {
            return "und";
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        return c.length() > 3 ? c.substring(0, 2) : c;
    }
}
