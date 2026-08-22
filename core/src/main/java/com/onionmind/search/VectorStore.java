package com.onionmind.search;

import java.util.List;

/** Abstraction over the vector database so callers (and tests) don't depend on Qdrant directly. */
public interface VectorStore {
    void ensureCollection();

    void upsert(List<EmbeddingPoint> points);

    List<SemanticSearchHit> search(float[] queryVector, int topK);

    /** SDD Fase 2, sec. 3.5 — the cost gate. Keyed by url: always available pre-persistence. */
    boolean hasUnchangedEmbedding(String url, String contentHash);

    void deleteByUrl(String url);
}
