package com.onionmind.ingestion;

import com.onionmind.content.ExtractedEntity;

import java.net.URI;
import java.util.List;

/**
 * A page finished AI enrichment (status {@code processed}) — published by
 * AiEnrichmentBackfillJob in the transaction that persists it (ADR-009). Fired after
 * enrichment, not indexing, because both consumers need its output: graph projects the
 * entities, intelligence diffs entities and category between versions. Carries everything
 * they need so neither reads another module's tables (design D2).
 */
public record PageEnrichedEvent(long pageId, String url, String host, int version, String contentHash,
                                String category, double categoryConfidence, List<ExtractedEntity> entities) {

    public PageEnrichedEvent {
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
