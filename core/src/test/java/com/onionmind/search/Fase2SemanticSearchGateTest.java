package com.onionmind.search;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Embedding;
import com.onionmind.ai.Entity;
import com.onionmind.ai.LanguageDetection;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.Translation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 2 exit gate (roadmap, master-sdd.md sec. 5): "busca por conceito encontra o que
 * full-text não encontrava". Qdrant/Ollama aren't wired into Testcontainers here — same as
 * QdrantVectorStoreTest/EmbeddingProcessorTest, this exercises the real fusion/API wiring
 * against fakes standing in for the two AI-backed dependencies, not the AI itself.
 */
@Import({TestcontainersConfiguration.class, Fase2SemanticSearchGateTest.FakeSemanticDependencies.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "embedding.enabled=true")
@AutoConfigureTestRestTemplate
class Fase2SemanticSearchGateTest {

    private static final String CONCEPT_PAGE_URL = "http://concept-only.onion/";
    // Text that never contains the literal query term "criptomoedas" (or any stem of it) —
    // full-text search must be structurally unable to find this page for that query.
    private static final String CONCEPT_PAGE_TEXT = "forum sobre moeda digital anonima e blockchain descentralizado";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String baseUrl() {
        return "http://localhost:" + port + "/api";
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM page_diffs");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
        jdbc.update("""
            INSERT INTO pages (url, source_type, raw_html, extracted_text, content_hash, version)
            VALUES (?, 'tor', ?, ?, ?, 1)
            """, CONCEPT_PAGE_URL, "<html>" + CONCEPT_PAGE_TEXT + "</html>", CONCEPT_PAGE_TEXT, "hash-concept");
    }

    @Test
    void fullTextSearchCannotFindThePageForTheConceptQuery() {
        // Proven directly against the same tsvector/tsquery machinery SearchController's
        // full-text branch runs — this is the "full-text não encontrava" half of the gate.
        Boolean lexicalMatch = jdbc.queryForObject("""
            SELECT search_vector @@ websearch_to_tsquery('simple', 'criptomoedas')
            FROM pages WHERE url = ?
            """, Boolean.class, CONCEPT_PAGE_URL);

        assertThat(lexicalMatch).isFalse();
    }

    @Test
    void semanticEndpointFindsWhatFullTextCannot() {
        var response = rest.getForEntity(baseUrl() + "/search/semantic?q=criptomoedas", SearchResponse.class);

        assertThat(response.getBody().results()).extracting(SearchResult::url).contains(CONCEPT_PAGE_URL);
    }

    @Test
    void hybridSearchFusesTheSemanticHitIntoTheDefaultEndpoint() {
        var response = rest.getForEntity(baseUrl() + "/search?q=criptomoedas&size=20", SearchResponse.class);

        assertThat(response.getBody().results()).extracting(SearchResult::url).contains(CONCEPT_PAGE_URL);
    }

    @TestConfiguration
    static class FakeSemanticDependencies {

        @Bean
        @Primary
        VectorStore fakeVectorStore() {
            return new VectorStore() {
                @Override
                public void ensureCollection() {
                }

                @Override
                public void upsert(List<EmbeddingPoint> points) {
                }

                @Override
                public List<SemanticSearchHit> search(float[] queryVector, int topK) {
                    // Stands in for real cosine similarity: the fake always resolves the
                    // concept query to the page whose text never contains the literal term.
                    return List.of(new SemanticSearchHit(CONCEPT_PAGE_URL, 0, 0.93));
                }

                @Override
                public boolean hasUnchangedEmbedding(String url, String contentHash) {
                    return false;
                }

                @Override
                public void deleteByUrl(String url) {
                }
            };
        }

        @Bean
        @Primary
        AIOrchestrator fakeAIOrchestrator() {
            return new AIOrchestrator() {
                @Override
                public Summary summarize(String text, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Classification classify(String text, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Translation translate(String text, String targetLang, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public LanguageDetection detectLanguage(String text, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<Entity> extractEntities(String text, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Summary summarizeDiff(String previousText, String currentText, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Embedding embed(String text, TaskContext ctx) {
                    return new Embedding(new float[]{0.1f, 0.2f, 0.3f}, 1.0);
                }
            };
        }
    }
}
