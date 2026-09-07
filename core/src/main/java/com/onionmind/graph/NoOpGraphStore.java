package com.onionmind.graph;

import com.onionmind.ai.Entity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Active when {@code graph.enabled=false} (the default) — {@code search}'s read endpoint still
 * needs a {@link GraphStore} bean to depend on. Unlike {@code NoOpAIOrchestrator}, a disabled
 * graph is a legitimate "there is nothing here" for a read, not an error worth throwing for.
 */
@Component
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpGraphStore implements GraphStore {

    @Override
    public boolean hasProcessedContent(String url, String contentHash) {
        return false;
    }

    @Override
    public void upsertEntities(String pageUrl, String contentHash, List<Entity> entities) {
        // no-op: EntityExtractionListener isn't registered when graph.enabled=false either
    }

    @Override
    public List<Entity> findEntitiesByPage(String url) {
        return List.of();
    }
}
