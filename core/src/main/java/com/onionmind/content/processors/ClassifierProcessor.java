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
 * A category outside the taxonomy is rejected as a failure, which re-queues the page for the
 * backfill job (spec content/ai-enrichment).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class ClassifierProcessor extends AbstractAiProcessor {

    /** fase3-sdd 7.6 — start here, refine with real data. */
    static final Set<String> TAXONOMY =
        Set.of("marketplace", "forum", "blog", "service", "institutional", "other");

    public ClassifierProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate) {
        super(orchestrator, gate);
    }

    @Override
    protected Document enrich(Document document, String text) {
        Classification result = orchestrator.classify(text,
            TaskContext.batch(TaskContext.TaskType.CLASSIFY, approxTokens(text), "pt", false));

        String category = result.category() == null ? "" : result.category().trim().toLowerCase(Locale.ROOT);
        if (!TAXONOMY.contains(category)) {
            throw new IllegalStateException("category '" + result.category() + "' outside taxonomy");
        }
        return document.withEnrichment(document.enrichment()
            .withCategory(new Classification(category, result.confidence())));
    }

    @Override
    public int order() {
        return 40;
    }
}
