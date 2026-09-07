package com.onionmind.graph;

import com.onionmind.ai.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jGraphStoreTest {

    private Neo4jGraphStore store;

    @BeforeEach
    void setUp() {
        Neo4jTestSupport.purgeAll();
        store = new Neo4jGraphStore(Neo4jTestSupport.driver());
    }

    @Test
    void hasProcessedContentIsFalseForAnUnknownPage() {
        assertThat(store.hasProcessedContent("http://never-seen.onion", "hash1")).isFalse();
    }

    @Test
    void hasProcessedContentIsTrueOnceEntitiesWereUpserted() {
        store.upsertEntities("http://example.onion", "hash1",
            List.of(new Entity(Entity.EntityType.PERSON, "Ana", 0.9)));

        assertThat(store.hasProcessedContent("http://example.onion", "hash1")).isTrue();
        assertThat(store.hasProcessedContent("http://example.onion", "hash2")).isFalse(); // different hash, same url
    }

    @Test
    void findEntitiesByPageReturnsWhatWasUpserted() {
        store.upsertEntities("http://example.onion", "hash1", List.of(
            new Entity(Entity.EntityType.PERSON, "Ana", 0.9),
            new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.8)));

        assertThat(store.findEntitiesByPage("http://example.onion")).containsExactlyInAnyOrder(
            new Entity(Entity.EntityType.PERSON, "Ana", 0.9),
            new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.8));
    }

    @Test
    void findEntitiesByPageIsEmptyForAPageWithNoEntities() {
        assertThat(store.findEntitiesByPage("http://never-seen.onion")).isEmpty();
    }

    @Test
    void sameEntityAcrossTwoPagesMergesIntoOneNode() {
        store.upsertEntities("http://page-a.onion", "hashA",
            List.of(new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.9)));
        store.upsertEntities("http://page-b.onion", "hashB",
            List.of(new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.7)));

        try (var session = Neo4jTestSupport.driver().session()) {
            long walletCount = session.run("MATCH (w:CRYPTO_WALLET {value: '1A2b3C'}) RETURN count(w) AS c")
                .single().get("c").asLong();
            assertThat(walletCount).isEqualTo(1);

            long mentionCount = session.run("""
                MATCH (:CRYPTO_WALLET {value: '1A2b3C'})<-[:MENTIONS]-(p:Page) RETURN count(p) AS c
                """).single().get("c").asLong();
            assertThat(mentionCount).isEqualTo(2);
        }
    }

    @Test
    void entitiesOnTheSamePageGetACoOccursWithRelation() {
        store.upsertEntities("http://example.onion", "hash1", List.of(
            new Entity(Entity.EntityType.ORGANIZATION, "Acme", 0.9),
            new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.8)));

        try (var session = Neo4jTestSupport.driver().session()) {
            long relCount = session.run("""
                MATCH (:ORGANIZATION {value: 'Acme'})-[:CO_OCCURS_WITH]->(:CRYPTO_WALLET {value: '1A2b3C'})
                RETURN count(*) AS c
                """).single().get("c").asLong();
            assertThat(relCount).isEqualTo(1);
        }
    }

    @Test
    void noSelfRelationWhenTheSameEntityIsExtractedTwiceOnOnePage() {
        store.upsertEntities("http://example.onion", "hash1", List.of(
            new Entity(Entity.EntityType.PERSON, "Ana", 0.9),
            new Entity(Entity.EntityType.PERSON, "Ana", 0.85)));

        try (var session = Neo4jTestSupport.driver().session()) {
            long relCount = session.run("MATCH (:PERSON {value: 'Ana'})-[r:CO_OCCURS_WITH]->() RETURN count(r) AS c")
                .single().get("c").asLong();
            assertThat(relCount).isZero();
        }
    }

    @Test
    void upsertingWithNoEntitiesStillRecordsThePageAsProcessed() {
        store.upsertEntities("http://empty-page.onion", "hash1", List.of());

        assertThat(store.hasProcessedContent("http://empty-page.onion", "hash1")).isTrue();
        assertThat(store.findEntitiesByPage("http://empty-page.onion")).isEmpty();
    }
}
