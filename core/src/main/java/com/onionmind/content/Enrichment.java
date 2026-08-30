package com.onionmind.content;

import com.onionmind.ai.Classification;
import com.onionmind.ai.Summary;
import com.onionmind.ai.Translation;

/**
 * The AI-generated fields a page accumulates as it moves through the order 10-40
 * processors. Rides on {@link Document} so a later processor can read an earlier one's
 * output (the summarizer uses the translation when there is one). The ingestion pipeline
 * reads the final value and hands it to persistence.
 */
public record Enrichment(LanguageTag language, Summary summary, Classification category, Translation translation) {

    public static final Enrichment EMPTY = new Enrichment(null, null, null, null);

    public Enrichment withLanguage(LanguageTag value) {
        return new Enrichment(value, summary, category, translation);
    }

    public Enrichment withSummary(Summary value) {
        return new Enrichment(language, value, category, translation);
    }

    public Enrichment withCategory(Classification value) {
        return new Enrichment(language, summary, value, translation);
    }

    public Enrichment withTranslation(Translation value) {
        return new Enrichment(language, summary, category, value);
    }

    public boolean isEmpty() {
        return language == null && summary == null && category == null && translation == null;
    }

    /** Detected language of the page: {@code {"code": "...", "confidence": ...}} in JSONB. */
    public record LanguageTag(String code, double confidence) {
    }
}
