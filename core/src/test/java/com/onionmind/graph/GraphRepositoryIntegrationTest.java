package com.onionmind.graph;

import com.onionmind.content.EntityType;
import com.onionmind.content.ExtractedEntity;
import com.onionmind.ingestion.PageEnrichedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.neo4j.Neo4jContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** GraphRepository against a real Neo4j (Community, same major as docker-compose). */
class GraphRepositoryIntegrationTest {

    private static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");
    private static Driver driver;

    private static final ExtractedEntity WALLET =
        ExtractedEntity.of(EntityType.CRYPTO_WALLET, "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", 1.0);
    private static final ExtractedEntity ANA = ExtractedEntity.of(EntityType.PERSON, "Ana", 0.9);
    private static final ExtractedEntity BIA = ExtractedEntity.of(EntityType.PERSON, "Bia", 0.8);
    private static final ExtractedEntity MONERO = ExtractedEntity.of(EntityType.TECHNOLOGY, "Monero", 0.7);

    private GraphRepository graph;

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

    @BeforeEach
    void setUp() {
        graph = new GraphRepository(driver);
        graph.deleteAll();
    }

    private static PageEnrichedEvent page(String url, int version, ExtractedEntity... entities) {
        return new PageEnrichedEvent(1L, url, PageEnrichedEvent.hostOf(url), version, "h" + version, "marketplace",
            0.9, List.of(entities));
    }

    private long count(String cypher) {
        try (var session = driver.session()) {
            return session.run(cypher).single().get(0).asLong();
        }
    }

    private Map<String, Long> counts() {
        return Map.of(
            "nodes", count("MATCH (n) RETURN count(n)"),
            "rels", count("MATCH ()-[r]->() RETURN count(r)"));
    }

    @Test
    void twoPagesCitingTheSameWalletShareOneNode() {
        graph.project(page("http://a.onion/1", 1, WALLET, ANA));
        graph.project(page("http://b.onion/1", 1, WALLET));

        assertThat(count("MATCH (e:Entity:CryptoWallet) RETURN count(e)")).isEqualTo(1);
        assertThat(count("MATCH (:Page)-[:MENTIONS]->(:Entity {id: '" + WALLET.id() + "'}) RETURN count(*)"))
            .isEqualTo(2);
        assertThat(count("MATCH (s:OnionService) RETURN count(s)")).isEqualTo(2);

        var view = graph.find(WALLET.id()).orElseThrow();
        assertThat(view.type()).isEqualTo("crypto_wallet");
        assertThat(view.pages()).extracting(EntityView.PageMention::url)
            .containsExactlyInAnyOrder("http://a.onion/1", "http://b.onion/1");
        assertThat(view.related()).extracting(EntityView.Related::key).containsExactly("ana");
    }

    @Test
    void newVersionReplacesMentionsAndDropsOrphanEntities() {
        graph.project(page("http://a.onion/1", 1, WALLET, ANA));

        assertThat(graph.project(page("http://a.onion/1", 2, ANA))).isTrue();

        assertThat(graph.find(WALLET.id())).isEmpty();
        assertThat(graph.find(ANA.id()).orElseThrow().pages()).singleElement()
            .satisfies(p -> assertThat(p.version()).isEqualTo(2));
    }

    @Test
    void olderOrSameVersionChangesNothing() {
        graph.project(page("http://a.onion/1", 2, ANA));
        var before = counts();

        assertThat(graph.project(page("http://a.onion/1", 1, WALLET))).isFalse();
        assertThat(graph.project(page("http://a.onion/1", 2, ANA))).isFalse();

        assertThat(counts()).isEqualTo(before);
        assertThat(graph.find(WALLET.id())).isEmpty();
    }

    @Test
    void quarantineRemovesThePageAndOnlyItsOrphans() {
        graph.project(page("http://a.onion/1", 1, WALLET, ANA));
        graph.project(page("http://b.onion/1", 1, WALLET));

        graph.removePage("http://a.onion/1");

        assertThat(graph.find(ANA.id())).isEmpty();
        assertThat(graph.find(WALLET.id()).orElseThrow().pages()).extracting(EntityView.PageMention::url)
            .containsExactly("http://b.onion/1");
        assertThat(count("MATCH (s:OnionService {host: 'a.onion'}) RETURN count(s)")).isZero();
    }

    @Test
    void coOccurrenceIsRankedBySharedPages() {
        graph.project(page("http://a.onion/1", 1, WALLET, ANA, MONERO));
        graph.project(page("http://b.onion/1", 1, WALLET, ANA));
        graph.project(page("http://c.onion/1", 1, WALLET, BIA));

        assertThat(graph.find(WALLET.id()).orElseThrow().related())
            .extracting(r -> r.key() + "=" + r.sharedPages())
            .containsExactly("ana=2", "bia=1", "monero=1");
    }

    @Test
    void cypherInKeyIsJustALiteral() {
        graph.project(page("http://a.onion/1", 1, ANA));
        var before = counts();

        assertThat(graph.find("person:x'}) MATCH (n) DETACH DELETE n //")).isEmpty();

        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void pageWithoutEntitiesIsStillProjected() {
        graph.project(page("http://a.onion/1", 1));

        assertThat(count("MATCH (p:Page)-[:HOSTED_ON]->(:OnionService) RETURN count(p)")).isEqualTo(1);
    }
}
