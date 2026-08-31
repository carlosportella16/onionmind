package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Classification;
import com.onionmind.ai.TaskContext;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * order 40 — classifies the page into a small fixed taxonomy and writes {@code pages.category}.
 * The model's answer is a hint: anything outside the taxonomy collapses to {@code "other"}
 * (the designed catch-all), so classification never fails a page. Refine the taxonomy with
 * real data (fase3-sdd 7.6).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class ClassifierProcessor extends AbstractAiProcessor {

    static final Set<String> TAXONOMY =
        Set.of("marketplace", "forum", "blog", "service", "institutional", "other");

    public ClassifierProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate) {
        super(orchestrator, gate);
    }

    private static final String ALLOWED =
        "Categorias permitidas (responda com exatamente uma, em minúsculas): "
            + String.join(", ", new java.util.TreeSet<>(TAXONOMY)) + ".\n\n";

    @Override
    protected Document enrich(Document document, String text) {
        Classification result = orchestrator.classify(ALLOWED + text,
            TaskContext.batch(TaskContext.TaskType.CLASSIFY, approxTokens(text), "pt", false));

        String raw = result.category() == null ? "" : result.category().trim().toLowerCase(Locale.ROOT);
        String category = TAXONOMY.contains(raw) ? raw : "other";
        double confidence = category.equals(raw) ? result.confidence() : Math.min(result.confidence(), 0.5);
        if (!category.equals(raw)) {
            log.debug("classifier: '{}' not in taxonomy for {} -> other", result.category(), document.url());
        }
        return document.withEnrichment(document.enrichment()
            .withCategory(new Classification(category, confidence)));
    }

    @Override
    public int order() {
        return 40;
    }
}
