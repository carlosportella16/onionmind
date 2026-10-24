package com.onionmind.graph;

import com.onionmind.ingestion.PageEnrichedEvent;
import com.onionmind.ingestion.PageQuarantinedEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Async, after commit, own transaction (ADR-009). An exception leaves the publication
 * incomplete, so EventResubmissionJob redelivers it — projection is idempotent.
 */
@Component
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
class GraphProjectionListener {

    private final GraphRepository graph;

    GraphProjectionListener(GraphRepository graph) {
        this.graph = graph;
    }

    @ApplicationModuleListener
    void on(PageEnrichedEvent event) {
        graph.project(event);
    }

    @ApplicationModuleListener
    void on(PageQuarantinedEvent event) {
        graph.removePage(event.url());
    }
}
