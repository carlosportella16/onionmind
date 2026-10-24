package com.onionmind.graph;

import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import com.onionmind.ingestion.PageEnrichedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code --graph.rebuild=true}: wipes Neo4j and reprojects every enriched page from
 * {@code pages.entities} — the graph is a derived view, Postgres the source of truth. Also how
 * pages enriched before Fase 4 (no event ever published for them) get into the graph. Reads
 * {@code pages} directly like the search module does; an admin path, not the event flow.
 */
@Component
@ConditionalOnProperty(prefix = "graph", name = {"enabled", "rebuild"}, havingValue = "true")
class GraphRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GraphRebuildRunner.class);

    private final JdbcTemplate jdbc;
    private final GraphRepository graph;
    private final JsonMapper mapper = JsonMapper.builder().build();

    GraphRebuildRunner(JdbcTemplate jdbc, GraphRepository graph) {
        this.jdbc = jdbc;
        this.graph = graph;
    }

    @Override
    public void run(ApplicationArguments args) {
        // ponytail: one DETACH DELETE + page-by-page projection; batch both if the graph nears AuraDB's 200K nodes.
        graph.deleteAll();
        List<PageEnrichedEvent> pages = jdbc.query("""
            SELECT id, url, version, content_hash, category->>'category' AS category,
                   coalesce((category->>'confidence')::float, 0) AS category_confidence, entities::text AS entities
            FROM pages
            WHERE ai_status = 'processed'
            ORDER BY id
            """, (rs, n) -> new PageEnrichedEvent(rs.getLong("id"), rs.getString("url"), PageEnrichedEvent.hostOf(rs.getString("url")),
            rs.getInt("version"), rs.getString("content_hash"), rs.getString("category"),
            rs.getDouble("category_confidence"), entities(rs.getString("entities"))));
        pages.forEach(graph::project);
        log.info("Graph rebuilt from Postgres: {} page(s) projected", pages.size());
    }

    private List<ExtractedEntity> entities(String json) {
        List<ExtractedEntity> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        for (JsonNode e : mapper.readTree(json)) {
            EntityType.fromCode(e.path("type").asString()).ifPresent(t -> out.add(new ExtractedEntity(
                t, e.path("key").asString(), e.path("display").asString(), e.path("confidence").asDouble())));
        }
        return out;
    }
}
