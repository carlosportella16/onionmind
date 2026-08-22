package com.onionmind.search;

import java.util.UUID;

/**
 * One embedded chunk, ready to upsert into Qdrant. Keyed by the page's URL — not a numeric
 * page id — because ContentProcessor.process() runs before the page is persisted (SDD Fase 2,
 * sec. 3.5): url is the only stable identity available at that point.
 */
public record EmbeddingPoint(
    UUID id, float[] vector, String url, int chunkIndex, String contentHash
) {
}
