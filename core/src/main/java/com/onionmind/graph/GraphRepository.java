package com.onionmind.graph;

import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import com.onionmind.ingestion.PageEnrichedEvent;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * All Cypher lives here, always parameterized — entity values come from crawled pages and
 * are never concatenated into a query. The only interpolated piece is the type label, which
 * comes from {@link EntityType}, never from input.
 *
 * <pre>
 * (:OnionService {host})&lt;-[:HOSTED_ON]-(:Page {url, version})-[:MENTIONS {confidence}]-&gt;(:Entity:CryptoWallet {id, type, key, display})
 * </pre>
 * Mentions hang off the page, not the service: a new version replaces one page's mentions
 * and quarantine removes one page, without recounting the rest of the service (design D5).
 */
@Repository
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
public class GraphRepository {

    static final int MAX_PAGES = 20;
    static final int MAX_RELATED = 10;

    private final Driver driver;
    private volatile boolean schemaReady;

    public GraphRepository(Driver driver) {
        this.driver = driver;
    }

    /**
     * Projects one enriched page version. A version not newer than what's already projected
     * (redelivery, out-of-order event) changes nothing.
     *
     * @return whether the graph changed
     */
    public boolean project(PageEnrichedEvent event) {
        ensureSchema();
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                int current = tx.run("""
                    MERGE (p:Page {url: $url}) ON CREATE SET p.version = 0
                    RETURN p.version AS version
                    """, Map.of("url", event.url())).single().get("version").asInt();
                if (current >= event.version()) {
                    return false;
                }
                tx.run("""
                    MATCH (p:Page {url: $url})
                    SET p.version = $version, p.category = $category, p.updatedAt = datetime()
                    """, params("url", event.url(), "version", event.version(), "category", event.category()));
                if (event.host() != null) {
                    tx.run("""
                        MATCH (p:Page {url: $url})
                        MERGE (s:OnionService {host: $host})
                        MERGE (p)-[:HOSTED_ON]->(s)
                        """, Map.of("url", event.url(), "host", event.host()));
                }
                List<String> ids = event.entities().stream().map(ExtractedEntity::id).toList();
                tx.run("""
                    MATCH (p:Page {url: $url})-[m:MENTIONS]->(e:Entity)
                    WHERE NOT e.id IN $ids
                    DELETE m
                    WITH DISTINCT e
                    WHERE NOT (e)<-[:MENTIONS]-()
                    DELETE e
                    """, Map.of("url", event.url(), "ids", ids));
                mergeMentions(tx, event.url(), event.entities());
                return true;
            });
        }
    }

    private static void mergeMentions(TransactionContext tx, String url, List<ExtractedEntity> entities) {
        Map<EntityType, List<ExtractedEntity>> byType =
            entities.stream().collect(Collectors.groupingBy(ExtractedEntity::type));
        byType.forEach((type, list) -> tx.run("""
            MATCH (p:Page {url: $url})
            UNWIND $entities AS ent
            MERGE (e:Entity {id: ent.id})
              ON CREATE SET e.type = ent.type, e.key = ent.key
            SET e:%s, e.display = ent.display
            MERGE (p)-[m:MENTIONS]->(e)
            SET m.confidence = ent.confidence
            """.formatted(label(type)), Map.of("url", url, "entities", list.stream()
                .map(e -> Map.<String, Object>of("id", e.id(), "type", e.type().code(), "key", e.key(),
                    "display", e.display(), "confidence", e.confidence()))
                .toList())));
    }

    /** Quarantine: the page, its mentions, and any entity or service only it referenced. */
    public void removePage(String url) {
        ensureSchema();
        try (var session = driver.session()) {
            session.executeWriteWithoutResult(tx -> tx.run("""
                MATCH (p:Page {url: $url})
                OPTIONAL MATCH (p)-[:MENTIONS]->(e:Entity)
                OPTIONAL MATCH (p)-[:HOSTED_ON]->(s:OnionService)
                WITH p, collect(DISTINCT e) + collect(DISTINCT s) AS touched
                DETACH DELETE p
                WITH touched
                UNWIND touched AS n
                WITH n WHERE NOT (n)--()
                DELETE n
                """, Map.of("url", url)));
        }
    }

    public Optional<EntityView> find(String id) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> {
                var result = tx.run("""
                    MATCH (e:Entity {id: $id})
                    OPTIONAL MATCH (e)<-[m:MENTIONS]-(p:Page)
                    OPTIONAL MATCH (p)-[:HOSTED_ON]->(s:OnionService)
                    WITH e, p, m, s ORDER BY p.updatedAt DESC
                    WITH e, collect(CASE WHEN p IS NULL THEN NULL ELSE
                        {url: p.url, host: s.host, confidence: m.confidence, version: p.version} END) AS pages
                    RETURN e.type AS type, e.key AS key, e.display AS display, pages[0..$maxPages] AS pages
                    """, Map.of("id", id, "maxPages", MAX_PAGES));
                if (!result.hasNext()) {
                    return Optional.empty();
                }
                Record r = result.single();
                List<EntityView.PageMention> pages = r.get("pages").asList(v -> new EntityView.PageMention(
                    v.get("url").asString(), v.get("host").isNull() ? null : v.get("host").asString(),
                    v.get("confidence").asDouble(), v.get("version").asInt()));
                List<EntityView.Related> related = tx.run("""
                    MATCH (e:Entity {id: $id})<-[:MENTIONS]-(p:Page)-[:MENTIONS]->(o:Entity)
                    WHERE o <> e
                    WITH o, count(DISTINCT p) AS shared
                    RETURN o.type AS type, o.key AS key, o.display AS display, shared
                    ORDER BY shared DESC, o.id
                    LIMIT $maxRelated
                    """, Map.of("id", id, "maxRelated", MAX_RELATED)).list(x -> new EntityView.Related(
                    x.get("type").asString(), x.get("key").asString(), x.get("display").asString(),
                    x.get("shared").asInt()));
                return Optional.of(new EntityView(r.get("type").asString(), r.get("key").asString(),
                    r.get("display").asString(), pages, related));
            });
        }
    }

    /**
     * Ids of the entities mentioned by the most of {@code urls}, ties by id — the agents'
     * {@code entities} tool asks this of the pages it found for a topic, then {@link #find} each.
     */
    public List<String> mostMentionedIn(Collection<String> urls, int limit) {
        try (var session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                MATCH (p:Page)-[:MENTIONS]->(e:Entity)
                WHERE p.url IN $urls
                WITH e, count(DISTINCT p) AS pages
                RETURN e.id AS id
                ORDER BY pages DESC, e.id
                LIMIT $limit
                """, Map.of("urls", List.copyOf(urls), "limit", limit)).list(r -> r.get("id").asString()));
        }
    }

    /** Wipes the projection — only for {@link GraphRebuildRunner}. */
    void deleteAll() {
        try (var session = driver.session()) {
            session.executeWriteWithoutResult(tx -> tx.run("MATCH (n) DETACH DELETE n"));
        }
    }

    /**
     * Uniqueness constraints (single property: composite node keys are Enterprise-only).
     * Created lazily on first write so a Neo4j outage at boot doesn't stop the app; concurrent
     * listeners rely on them so MERGE never duplicates an entity.
     */
    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        try (var session = driver.session()) {
            session.executeWriteWithoutResult(tx -> {
                tx.run("CREATE CONSTRAINT entity_id IF NOT EXISTS FOR (e:Entity) REQUIRE e.id IS UNIQUE");
                tx.run("CREATE CONSTRAINT page_url IF NOT EXISTS FOR (p:Page) REQUIRE p.url IS UNIQUE");
                tx.run("CREATE CONSTRAINT service_host IF NOT EXISTS FOR (s:OnionService) REQUIRE s.host IS UNIQUE");
            });
        }
        schemaReady = true;
    }

    /** CRYPTO_WALLET -> CryptoWallet. */
    static String label(EntityType type) {
        return Arrays.stream(type.name().split("_"))
            .map(w -> w.charAt(0) + w.substring(1).toLowerCase(java.util.Locale.ROOT))
            .collect(Collectors.joining());
    }

    private static Map<String, Object> params(Object... kv) {
        // Map.of rejects null values; category can be null.
        Map<String, Object> map = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
