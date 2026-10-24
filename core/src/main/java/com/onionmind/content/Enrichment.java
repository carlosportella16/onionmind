package com.onionmind.content;

import com.onionmind.ai.Classification;
import com.onionmind.ai.Summary;

import java.util.List;

/**
 * The AI-generated fields a page accumulates as it moves through the order 10-50
 * processors. Rides on {@link Document} so a later processor can read an earlier one's
 * output. The ingestion pipeline reads the final value and hands it to persistence.
 * {@code entities} (Fase 4, order 50) is never null — empty until EntityProcessor runs.
 */
public record Enrichment(LanguageTag language, Summary summary, Classification category,
                         List<ExtractedEntity> entities) {

    public static final Enrichment EMPTY = new Enrichment(null, null, null, List.of());

    public Enrichment {
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public Enrichment(LanguageTag language, Summary summary, Classification category) {
        this(language, summary, category, List.of());
    }

    public Enrichment withLanguage(LanguageTag value) {
        return new Enrichment(value, summary, category, entities);
    }

    public Enrichment withSummary(Summary value) {
        return new Enrichment(language, value, category, entities);
    }

    public Enrichment withCategory(Classification value) {
        return new Enrichment(language, summary, value, entities);
    }

    public Enrichment withEntities(List<ExtractedEntity> value) {
        return new Enrichment(language, summary, category, value);
    }

    public boolean isEmpty() {
        return language == null && summary == null && category == null && entities.isEmpty();
    }

    /** Detected language of the page: {@code {"code": "...", "confidence": ...}} in JSONB. */
    public record LanguageTag(String code, double confidence) {
    }
}
