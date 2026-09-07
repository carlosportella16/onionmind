package com.onionmind.graph;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Entity;
import com.onionmind.ai.TaskContext;
import com.onionmind.ingestion.PageContentLookup;
import com.onionmind.ingestion.PageIndexedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Reacts to {@code PageIndexedEvent} (ADR-009) — {@code ingestion} never knows this exists.
 * Never touches {@code ingestion.internal.PageRepository} directly, only the public
 * {@link PageContentLookup} port (design.md D3).
 */
@Component
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
class EntityExtractionListener {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractionListener.class);

    private final GraphStore graphStore;
    private final PageContentLookup contentLookup;
    private final AIOrchestrator orchestrator;

    EntityExtractionListener(GraphStore graphStore, PageContentLookup contentLookup, AIOrchestrator orchestrator) {
        this.graphStore = graphStore;
        this.contentLookup = contentLookup;
        this.orchestrator = orchestrator;
    }

    @ApplicationModuleListener
    void on(PageIndexedEvent event) {
        try {
            if (graphStore.hasProcessedContent(event.url(), event.contentHash())) {
                return; // content_hash gate — same pattern as AiEnrichmentGate, self-contained in the graph
            }

            Optional<String> text = contentLookup.currentText(event.url());
            if (text.isEmpty() || text.get().isBlank()) {
                return;
            }

            List<Entity> entities = orchestrator.extractEntities(text.get(),
                TaskContext.batch(TaskContext.TaskType.EXTRACT_ENTITIES, text.get().length() / 4, null, false));
            graphStore.upsertEntities(event.url(), event.contentHash(), entities);
        } catch (RuntimeException e) {
            // The page is already indexed and searchable by this point — a graph/LLM failure
            // here must never look like an ingestion failure. Left unprocessed, the same hash
            // is retried on the next re-crawl (content_hash gate above lets it through again).
            log.warn("Entity extraction failed for {}: {}", event.url(), e.getMessage());
        }
    }
}
