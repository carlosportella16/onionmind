package com.onionmind.search;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Embedding;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticSearchServiceTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(db);
        jdbc.execute("""
            CREATE TABLE pages (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                url VARCHAR(500) NOT NULL,
                source_type VARCHAR(50) NOT NULL,
                extracted_text CLOB,
                version INT NOT NULL,
                first_seen_at TIMESTAMP NOT NULL,
                last_seen_at TIMESTAMP NOT NULL
            )
            """);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    private void insertPage(String url, String text) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, version, first_seen_at, last_seen_at)
            VALUES (?, 'tor', ?, 1, now(), now())
            """, url, text);
    }

    @Test
    void unavailableWhenEitherDependencyIsMissing() {
        var service = new SemanticSearchService(Optional.empty(), Optional.of(new FakeAIOrchestrator()), jdbc, 0.5);

        assertThat(service.isAvailable()).isFalse();
        assertThat(service.search("anything", 5)).isEmpty();
    }

    @Test
    void hydratesVectorHitsWithPageMetadataFromPostgres() {
        insertPage("http://concept.onion", "conteudo sobre moeda digital anonima");
        var store = new FakeVectorStore(List.of(new SemanticSearchHit("http://concept.onion", 0, 0.87)));
        var service = new SemanticSearchService(Optional.of(store), Optional.of(new FakeAIOrchestrator()), jdbc, 0.5);

        var results = service.search("criptomoedas", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).url()).isEqualTo("http://concept.onion");
        assertThat(results.get(0).rank()).isEqualTo(0.87);
        assertThat(results.get(0).snippet()).contains("moeda digital anonima");
    }

    @Test
    void keepsBestScorePerUrlWhenMultipleChunksMatch() {
        insertPage("http://multi.onion", "texto longo com dois chunks relevantes");
        var store = new FakeVectorStore(List.of(
            new SemanticSearchHit("http://multi.onion", 0, 0.4),
            new SemanticSearchHit("http://multi.onion", 1, 0.9)
        ));
        var service = new SemanticSearchService(Optional.of(store), Optional.of(new FakeAIOrchestrator()), jdbc, 0.5);

        var results = service.search("query", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).rank()).isEqualTo(0.9);
    }

    @Test
    void hitsBelowMinScoreAreDroppedEvenIfQdrantReturnedThem() {
        // Qdrant's k-NN always returns topK nearest points, even when nothing is relevant —
        // the service must apply its own relevance floor rather than trusting every hit.
        insertPage("http://unrelated.onion", "completely unrelated content");
        var store = new FakeVectorStore(List.of(new SemanticSearchHit("http://unrelated.onion", 0, 0.2)));
        var service = new SemanticSearchService(Optional.of(store), Optional.of(new FakeAIOrchestrator()), jdbc, 0.5);

        assertThat(service.search("query", 5)).isEmpty();
    }

    @Test
    void hitsAtOrAboveMinScoreAreKept() {
        insertPage("http://relevant.onion", "relevant content");
        var store = new FakeVectorStore(List.of(new SemanticSearchHit("http://relevant.onion", 0, 0.5)));
        var service = new SemanticSearchService(Optional.of(store), Optional.of(new FakeAIOrchestrator()), jdbc, 0.5);

        assertThat(service.search("query", 5)).hasSize(1);
    }

    @Test
    void noVectorHitsReturnsEmptyWithoutQueryingPostgres() {
        var store = new FakeVectorStore(List.of());
        var service = new SemanticSearchService(Optional.of(store), Optional.of(new FakeAIOrchestrator()), jdbc, 0.5);

        assertThat(service.search("nothing matches", 5)).isEmpty();
    }

    private static class FakeAIOrchestrator implements AIOrchestrator {
        @Override
        public Summary summarize(String text, TaskContext ctx) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Embedding embed(String text, TaskContext ctx) {
            return new Embedding(new float[]{0.1f, 0.2f, 0.3f}, 1.0);
        }
    }

    private static class FakeVectorStore implements VectorStore {
        private final List<SemanticSearchHit> hits;

        FakeVectorStore(List<SemanticSearchHit> hits) {
            this.hits = hits;
        }

        @Override
        public void ensureCollection() {
        }

        @Override
        public void upsert(List<EmbeddingPoint> points) {
        }

        @Override
        public List<SemanticSearchHit> search(float[] queryVector, int topK) {
            return hits;
        }

        @Override
        public boolean hasUnchangedEmbedding(String url, String contentHash) {
            return false;
        }

        @Override
        public void deleteByUrl(String url) {
        }
    }
}
