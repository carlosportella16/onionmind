package com.onionmind.search;

import java.time.Instant;

/**
 * {@code summary} and {@code category} are the Fase 3 enrichment, read straight from the
 * {@code pages} JSONB columns — the search path never calls an LLM. Both are null for a
 * page that has not been enriched yet.
 */
public record SearchResult(
    Long id, String url, String sourceType, String snippet,
    Double rank, Integer version, Instant firstSeenAt, Instant lastSeenAt,
    String summary, String category
) {}
