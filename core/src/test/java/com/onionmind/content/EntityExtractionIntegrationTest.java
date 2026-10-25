package com.onionmind.content;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Classification;
import com.onionmind.ai.Embedding;
import com.onionmind.ai.EntityExtraction;
import com.onionmind.ai.LanguageDetection;
import com.onionmind.ai.NamedEntity;
import com.onionmind.ai.Summary;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.Translation;
import com.onionmind.ai.decorator.CacheDecorator;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import com.onionmind.ingestion.AiEnrichmentBackfillJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EntityProcessor wired into the real backfill against Postgres: a page with a valid wallet
 * and a named person ends up with both in {@code pages.entities}. The LLM is a fake
 * AIOrchestrator, as in Fase3AiGenerationGateTest.
 */
@Import({TestcontainersConfiguration.class, EntityExtractionIntegrationTest.FakeAi.class})
@SpringBootTest(properties = "ai.enabled=true")
class EntityExtractionIntegrationTest {

    private static final String WALLET = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AiEnrichmentBackfillJob backfillJob;

    @MockitoBean
    private ProviderQuotaTracker quotaTracker;
    @MockitoBean
    private CacheDecorator cacheDecorator;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM page_changes");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
    }

    @Test
    void walletAndPersonArePersistedWithKeysAndConfidences() {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status)
            VALUES ('http://loja.onion/', 'tor', ?, 'h1', 1, 'pending')
            """, "Fale com José Silva, pagamento em " + WALLET + ".");

        backfillJob.run();

        List<Map<String, Object>> entities = jdbc.queryForList("""
            SELECT e->>'type' AS type, e->>'key' AS key, (e->>'confidence')::float AS confidence
            FROM pages, jsonb_array_elements(entities) e WHERE url = 'http://loja.onion/'
            """);
        assertThat(entities).containsExactlyInAnyOrder(
            Map.of("type", "crypto_wallet", "key", WALLET, "confidence", 1.0),
            Map.of("type", "person", "key", "jose silva", "confidence", 0.9));
        assertThat(jdbc.queryForObject("SELECT ai_status FROM pages WHERE url = 'http://loja.onion/'", String.class))
            .isEqualTo("processed");
    }

    @TestConfiguration
    static class FakeAi {

        @Bean
        @Primary
        AIOrchestrator fakeOrchestrator() {
            return new AIOrchestrator() {
                @Override
                public Summary summarize(String text, TaskContext ctx) {
                    return new Summary("resumo", 0.9);
                }

                @Override
                public Classification classify(String text, TaskContext ctx) {
                    return new Classification("marketplace", 0.9);
                }

                @Override
                public Translation translate(String text, String targetLang, TaskContext ctx) {
                    return new Translation(text, "pt", 0.9);
                }

                @Override
                public LanguageDetection detectLanguage(String text, TaskContext ctx) {
                    return new LanguageDetection("pt", 0.9);
                }

                @Override
                public EntityExtraction extractEntities(String text, TaskContext ctx) {
                    return new EntityExtraction(text.contains("José Silva")
                        ? List.of(new NamedEntity("person", "José Silva", 0.9)) : List.of(), 0.9);
                }

                @Override
                public com.onionmind.ai.QueryRewrite rewriteQuery(String question, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public com.onionmind.ai.Answer answer(String question, java.util.List<String> sources, TaskContext ctx) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Embedding embed(String text, TaskContext ctx) {
                    throw new UnsupportedOperationException("not used here");
                }
            };
        }
    }
}
