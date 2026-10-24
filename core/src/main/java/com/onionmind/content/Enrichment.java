package com.onionmind.content;

import com.onionmind.ai.Classification;
import com.onionmind.ai.Summary;
import com.onionmind.ai.Translation;

import java.util.List;

/**
 * The AI-generated fields a page accumulates as it moves through the order 10-40
 * processors. Rides on {@link Document} so a later processor can read an earlier one's
 * output (the summarizer uses the translation when there is one). The ingestion pipeline
 * reads the final value and hands it to persistence. {@code entities} (Fase 4, order 50)
 * is never null — empty until EntityProcessor runs.
 */
public record Enrichment(LanguageTag language, Summary summary, Classification category, Translation translation,
                         List<ExtractedEntity> entities) {

    public static final Enrichment EMPTY = new Enrichment(null, null, null, null, List.of());

    public Enrichment {
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public Enrichment(LanguageTag language, Summary summary, Classification category, Translation translation) {
        this(language, summary, category, translation, List.of());
    }

    public Enrichment withLanguage(LanguageTag value) {
        return new Enrichment(value, summary, category, translation, entities);
    }

    public Enrichment withSummary(Summary value) {
        return new Enrichment(language, value, category, translation, entities);
    }

    public Enrichment withCategory(Classification value) {
        return new Enrichment(language, summary, value, translation, entities);
    }

    public Enrichment withTranslation(Translation value) {
        return new Enrichment(language, summary, category, value, entities);
    }

    public Enrichment withEntities(List<ExtractedEntity> value) {
        return new Enrichment(language, summary, category, translation, value);
    }

    public boolean isEmpty() {
        return language == null && summary == null && category == null && translation == null && entities.isEmpty();
    }

    /** Detected language of the page: {@code {"code": "...", "confidence": ...}} in JSONB. */
    public record LanguageTag(String code, double confidence) {
    }
}
