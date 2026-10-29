package com.onionmind.agents;

import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import com.onionmind.graph.GraphRepository;
import com.onionmind.ingestion.PageEnrichedEvent;
import com.onionmind.search.PassageRetriever.Retrieval;
import com.onionmind.search.SearchResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.testcontainers.neo4j.Neo4jContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The {@code entities} tool against a real Neo4j, and without the graph. */
class EntitiesToolTest {

    private static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");
    private static Driver driver;

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

    private static PageEnrichedEvent page(String url, ExtractedEntity... entities) {
        return new PageEnrichedEvent(1L, url, PageEnrichedEvent.hostOf(url), 1, "h1", "marketplace", 0.9,
            List.of(entities));
    }

    private static Retrieval found(String... urls) {
        return new Retrieval(List.of(urls).stream().map(u -> new SearchResult(1L, u, "tor", "", 1.0, 1, null, null,
            null, null)).toList(), List.of());
    }

    @Test
    void entitiesOfTheTopicsPagesComeWithTheirPagesAndCoOccurrences() {
        var graph = new GraphRepository(driver);
        var ana = ExtractedEntity.of(EntityType.PERSON, "Ana Souza", 0.9);
        var shop = ExtractedEntity.of(EntityType.ORGANIZATION, "Mercado Sombrio", 0.9);
        graph.project(page("http://a.onion/", ana, shop));
        graph.project(page("http://b.onion/", ana));
        graph.project(page("http://unrelated.onion/", ExtractedEntity.of(EntityType.PERSON, "Bia", 0.9)));
        var tool = new EntitiesTool(new StaticListableBeanFactory(Map.of("graph", graph))
            .getBeanProvider(GraphRepository.class));

        List<AgentRun.Source> sources = tool.collect(null, found("http://a.onion/", "http://b.onion/"));

        assertThat(tool.available()).isTrue();
        assertThat(sources).extracting(AgentRun.Source::id).containsExactly("E1", "E2");
        assertThat(sources.getFirst()).satisfies(s -> {
            assertThat(s.tool()).isEqualTo("entities");
            assertThat(s.url()).isEqualTo("/api/entities/person/ana%20souza");
            assertThat(s.text()).startsWith("Entity: Ana Souza (person)")
                .contains("http://a.onion/", "http://b.onion/", "Appears with: Mercado Sombrio (organization, 1 shared pages)");
        });
        assertThat(sources).noneMatch(s -> s.text().contains("Bia"));
        assertThat(tool.collect(null, found())).isEmpty();
    }

    @Test
    void withoutTheGraphTheToolIsUnavailable() {
        var tool = new EntitiesTool(new StaticListableBeanFactory().getBeanProvider(GraphRepository.class));

        assertThat(tool.available()).isFalse();
    }
}
