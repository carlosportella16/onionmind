package com.onionmind.graph;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import com.onionmind.ingestion.PageEnrichedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.neo4j.Neo4jContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** --graph.rebuild=true: the graph is reproduced from pages.entities alone. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GraphRebuildRunnerTest {

    private static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");
    private static Driver driver;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        NEO4J.start();
        driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    }

    @AfterAll
    static void stop() {
        driver.close();
        NEO4J.stop();
    }

    @Test
    void wipedGraphComesBackFromPostgresWithTheSameShape() {
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status, category, entities)
            VALUES ('http://a.onion/', 'tor', 't', 'h', 2, 'processed', '{"category":"forum","confidence":0.9}',
                    '[{"type":"person","key":"ana","display":"Ana","confidence":0.9},
                      {"type":"crypto_wallet","key":"1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa","display":"1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa","confidence":1.0}]'),
                   ('http://b.onion/', 'tor', 't', 'h', 1, 'pending', NULL,
                    '[{"type":"person","key":"bia","display":"Bia","confidence":0.9}]')
            """);
        GraphRepository graph = new GraphRepository(driver);
        graph.project(new PageEnrichedEvent(9, "http://stale.onion/", "stale.onion", 1, "h", null, 0,
            List.of(ExtractedEntity.of(EntityType.PERSON, "Zé", 0.9))));

        new GraphRebuildRunner(jdbc, graph).run(null);

        var ana = graph.find("person:ana").orElseThrow();
        assertThat(ana.pages()).singleElement().satisfies(p -> {
            assertThat(p.url()).isEqualTo("http://a.onion/");
            assertThat(p.version()).isEqualTo(2);
        });
        assertThat(ana.related()).extracting(EntityView.Related::type).containsExactly("crypto_wallet");
        assertThat(graph.find("person:ze")).as("wiped first").isEmpty();
        assertThat(graph.find("person:bia")).as("only processed pages").isEmpty();
    }
}
