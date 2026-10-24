package com.onionmind.content.processors;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.EntityExtraction;
import com.onionmind.ai.NamedEntity;
import com.onionmind.ai.TaskContext;
import com.onionmind.content.AiEnrichmentGate;
import com.onionmind.content.Document;
import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * order 50 (Fase 4) — entities into {@code pages.entities}. Wallets, onion addresses, e-mails
 * and PGP fingerprints come from {@link EntityPatterns} (free, checksum-validated); people,
 * organizations, places and technologies from the LLM. If the LLM fails, the pattern
 * entities are kept and the step still fails, so the backfill retries the named ones.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class EntityProcessor extends AbstractAiProcessor {

    private final int maxInputChars;
    private final int maxPerPage;

    public EntityProcessor(AIOrchestrator orchestrator, AiEnrichmentGate gate,
                           @Value("${entities.max-input-chars:8000}") int maxInputChars,
                           @Value("${entities.max-per-page:200}") int maxPerPage) {
        super(orchestrator, gate);
        this.maxInputChars = maxInputChars;
        this.maxPerPage = maxPerPage;
    }

    @Override
    protected Document enrich(Document document, String text) {
        List<ExtractedEntity> found = new ArrayList<>(EntityPatterns.extract(text));
        String input = text.length() > maxInputChars ? text.substring(0, maxInputChars) : text;
        try {
            EntityExtraction named = orchestrator.extractEntities(input,
                TaskContext.batch(TaskContext.TaskType.EXTRACT_ENTITIES, approxTokens(input), null, false));
            for (NamedEntity n : named.entities()) {
                EntityType.fromCode(n.type()).ifPresent(t -> found.add(ExtractedEntity.of(t, n.value(), n.confidence())));
            }
        } catch (RuntimeException e) {
            throw new PartialFailure(withEntities(document, found), e);
        }
        return withEntities(document, found);
    }

    private Document withEntities(Document document, List<ExtractedEntity> found) {
        return document.withEnrichment(document.enrichment().withEntities(ExtractedEntity.dedupe(found, maxPerPage)));
    }

    @Override
    public int order() {
        return 50;
    }
}
