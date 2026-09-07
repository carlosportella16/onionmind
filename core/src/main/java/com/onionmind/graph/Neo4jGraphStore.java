package com.onionmind.graph;

import com.onionmind.ai.Entity;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Nodes/relations via the official Bolt driver, no object-graph mapping layer — same
 * minimalism as {@code JdbcTemplate} over JPA and the raw HTTP client for Qdrant (design.md
 * D5). Merge by business key (master-sdd sec. 10): {@code MERGE (w:CryptoWallet {value: $v})}.
 * The entity type's enum name doubles as the Neo4j label — Cypher can't parameterize labels,
 * but the six possible values come from a closed Java enum, never from raw text.
 */
@Component
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
class Neo4jGraphStore implements GraphStore {

    private final Driver driver;

    Neo4jGraphStore(Driver driver) {
        this.driver = driver;
    }

    @Override
    public boolean hasProcessedContent(String url, String contentHash) {
        try (Session session = driver.session()) {
            var result = session.run(
                "MATCH (p:Page {url: $url, contentHash: $contentHash}) RETURN count(p) AS c",
                Map.of("url", url, "contentHash", contentHash));
            return result.single().get("c").asLong() > 0;
        }
    }

    @Override
    public void upsertEntities(String pageUrl, String contentHash, List<Entity> entities) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (p:Page {url: $url}) SET p.contentHash = $contentHash",
                    Map.of("url", pageUrl, "contentHash", contentHash));

                for (Entity entity : entities) {
                    String label = entity.type().name();
                    tx.run("""
                        MERGE (e:%s {value: $value})
                        SET e.confidence = $confidence
                        WITH e
                        MATCH (p:Page {url: $url})
                        MERGE (p)-[:MENTIONS]->(e)
                        """.formatted(label),
                        Map.of("value", entity.value(), "url", pageUrl, "confidence", entity.confidence()));
                }

                for (int i = 0; i < entities.size(); i++) {
                    for (int j = i + 1; j < entities.size(); j++) {
                        linkCoOccurring(tx, entities.get(i), entities.get(j));
                    }
                }
                return null;
            });
        }
    }

    private void linkCoOccurring(org.neo4j.driver.TransactionContext tx, Entity a, Entity b) {
        if (a.type() == b.type() && a.value().equals(b.value())) {
            return; // same entity mentioned twice — nothing to relate it to but itself
        }
        tx.run("""
            MATCH (a:%s {value: $av}), (b:%s {value: $bv})
            MERGE (a)-[:CO_OCCURS_WITH]->(b)
            """.formatted(a.type().name(), b.type().name()),
            Map.of("av", a.value(), "bv", b.value()));
    }

    @Override
    public List<Entity> findEntitiesByPage(String url) {
        try (Session session = driver.session()) {
            var result = session.run("""
                MATCH (p:Page {url: $url})-[:MENTIONS]->(e)
                RETURN labels(e) AS labels, e.value AS value, e.confidence AS confidence
                """, Map.of("url", url));

            List<Entity> entities = new ArrayList<>();
            for (var record : result.list()) {
                Entity.EntityType type = labelToType(record.get("labels").asList(Value::asString));
                if (type == null) {
                    continue;
                }
                entities.add(new Entity(type, record.get("value").asString(), record.get("confidence").asDouble(0.0)));
            }
            return entities;
        }
    }

    private Entity.EntityType labelToType(List<String> labels) {
        for (String label : labels) {
            try {
                return Entity.EntityType.valueOf(label);
            } catch (IllegalArgumentException ignored) {
                // not one of ours (e.g. "Page") — keep looking
            }
        }
        return null;
    }
}
