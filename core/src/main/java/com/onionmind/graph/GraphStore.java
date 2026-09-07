package com.onionmind.graph;

import com.onionmind.ai.Entity;

import java.util.List;

/**
 * Public port onto the knowledge graph — {@code search} reads through here (design.md D8),
 * same pattern as {@code content.EmbeddingProcessor} depending on {@code search.VectorStore}.
 */
public interface GraphStore {

    /** The content_hash gate (RF-13-equivalent for entities): true once this exact content has been extracted. */
    boolean hasProcessedContent(String url, String contentHash);

    /** Merges the page and its entities by business key and records co-occurrence between them. */
    void upsertEntities(String pageUrl, String contentHash, List<Entity> entities);

    /** Entities mentioned by a page, empty if none were extracted (or the page isn't known to the graph). */
    List<Entity> findEntitiesByPage(String url);
}
