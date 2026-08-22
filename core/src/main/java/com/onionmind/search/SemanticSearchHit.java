package com.onionmind.search;

/** One ranked result from a vector similarity search, correlated back to Postgres by url. */
public record SemanticSearchHit(String url, int chunkIndex, double score) {
}
