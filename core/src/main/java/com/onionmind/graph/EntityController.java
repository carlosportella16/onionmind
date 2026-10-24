package com.onionmind.graph;

import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Who else cites this wallet, and what shows up next to it." The key is normalized the
 * same way extraction does, so {@code /person/José Silva} finds {@code person:jose silva}.
 */
@RestController
@RequestMapping("/api/entities")
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
class EntityController {

    private final GraphRepository graph;

    EntityController(GraphRepository graph) {
        this.graph = graph;
    }

    @GetMapping("/{type}/{key}")
    ResponseEntity<EntityView> get(@PathVariable String type, @PathVariable String key) {
        var entityType = EntityType.fromCode(type);
        if (entityType.isEmpty() || key.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String id = ExtractedEntity.of(entityType.get(), key, 0).id();
        return graph.find(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler({ServiceUnavailableException.class, SessionExpiredException.class})
    ResponseEntity<Void> graphUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
