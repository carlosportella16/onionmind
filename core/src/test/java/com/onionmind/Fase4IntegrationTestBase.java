package com.onionmind;

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
import com.onionmind.graph.GraphRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.neo4j.Neo4jContainer;

import java.util.List;

/**
 * One shared context for the Fase 4 integration tests: the real app with ai.enabled and
 * graph.enabled, Postgres/Kafka (TestcontainersConfiguration) + Neo4j, real async
 * @ApplicationModuleListeners, and a deterministic fake LLM. Subclasses add no annotations
 * so Spring reuses one context for all of them.
 */
@Import({TestcontainersConfiguration.class, Fase4IntegrationTestBase.Fase4Infrastructure.class})
@SpringBootTest(properties = {"ai.enabled=true", "graph.enabled=true"})
public abstract class Fase4IntegrationTestBase {

    @Autowired
    protected JdbcTemplate jdbc;

    @MockitoSpyBean
    protected GraphRepository graph;
    @MockitoBean
    private ProviderQuotaTracker quotaTracker;
    @MockitoBean
    private CacheDecorator cacheDecorator;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM event_publication");
        jdbc.update("DELETE FROM page_changes");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Fase4Infrastructure {

        @Bean
        @ServiceConnection
        Neo4jContainer neo4jContainer() {
            return new Neo4jContainer("neo4j:5-community");
        }

        /** Answers by content so scenarios are deterministic: forums say "fórum", everything else is a marketplace. */
        @Bean
        @Primary
        AIOrchestrator fakeOrchestrator() {
            return new AIOrchestrator() {
                @Override
                public Summary summarize(String text, TaskContext ctx) {
                    if (text.startsWith("Resuma em 1 a 3 frases o que mudou")) {
                        int at = text.indexOf("Trechos adicionados:\n");
                        String added = at < 0 ? "nada novo" : text.substring(at + 21).lines().findFirst().orElse("");
                        return new Summary("Mudança: " + added, 0.9);
                    }
                    return new Summary("resumo da página", 0.9);
                }

                @Override
                public Classification classify(String text, TaskContext ctx) {
                    return new Classification(text.toLowerCase().contains("fórum") ? "forum" : "marketplace", 0.9);
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
                    return new EntityExtraction(text.contains("Ana Souza")
                        ? List.of(new NamedEntity("person", "Ana Souza", 0.9)) : List.of(), 0.9);
                }

                @Override
                public Embedding embed(String text, TaskContext ctx) {
                    throw new UnsupportedOperationException("embeddings are off in these tests");
                }
            };
        }
    }
}
