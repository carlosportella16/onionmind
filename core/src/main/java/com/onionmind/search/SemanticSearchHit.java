package com.onionmind.search;

/**
 * One ranked result from a vector similarity search, correlated back to Postgres by url.
 * {@code text} is the chunk from the payload (phase5-rag-chat D3), null for a point written
 * without it — the chat then cites a ts_headline window instead.
 */
public record SemanticSearchHit(String url, int chunkIndex, double score, String text) {
}
