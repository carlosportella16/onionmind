package com.onionmind.graph;

import com.onionmind.ai.Entity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read endpoint for this module's own data (design.md D8, revised). Originally this lived in
 * {@code search}, following the same pattern as {@code content.EmbeddingProcessor} depending on
 * {@code search.VectorStore} — but {@code search} already depends on {@code content}
 * transitively (content -> search), so a {@code search -> graph} edge combined with this
 * module's own {@code graph -> ingestion} dependency closed a 4-module cycle
 * (content -> search -> graph -> ingestion -> content). Each new Phase 4 module exposing its
 * own endpoint instead keeps every dependency one-directional.
 */
@RestController
@RequestMapping("/api/pages")
public class EntityController {

    private final GraphStore graphStore;

    public EntityController(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    @GetMapping("/entities")
    public ResponseEntity<List<Entity>> entities(@RequestParam String url) {
        if (url.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(graphStore.findEntitiesByPage(url));
    }
}
