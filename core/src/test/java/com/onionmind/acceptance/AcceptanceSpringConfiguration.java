package com.onionmind.acceptance;

import com.onionmind.TestcontainersConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.neo4j.Neo4jContainer;

/**
 * One Spring context for every acceptance scenario (design D3): Fases 1-5 all switched on,
 * real Postgres/Kafka/Redis/Qdrant/Neo4j and the real local embedding model, and only the
 * generation endpoints faked (D4). Backfill
 * schedules are pushed out of the way — scenarios trigger them explicitly (D6).
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ai.enabled=true",
    "embedding.enabled=true",
    "graph.enabled=true",
    "ai.backfill.interval-ms=3600000",
    "embedding.backfill-interval-ms=3600000",
    "ai.retry.backoff=10ms",
    // Fase 5: the slow-answer scenario must not wait the production 12 s.
    "chat.answer-timeout=2s",
    "ai.openai-compatible.deepseek.api-key=acceptance-test-key",
    "ai.gemini.api-key=acceptance-test-key",
    // The shipped ladder is DeepSeek alone; the Fase 3 scenarios still exercise a two-rung ladder
    // (a parked first rung falling to the second), which is how Gemini comes back with billing.
    "ai.ladder=gemini,deepseek",
    // Scenarios must not depend on the real tier's ceilings in application.yml: at the free
    // tier's 15 RPM a multi-page scenario spills to DeepSeek depending on the runner's speed.
    "ai.providers.gemini.requests-per-minute=1000",
    "ai.providers.gemini.requests-per-day=100000",
    "ai.providers.gemini.tokens-per-minute=10000000",
    // ~200 chars per chunk: a one-sentence page is one chunk, the link list spans several
    "embedding.chunk-size=50",
    "illegal-content.url-denylist-path=classpath:acceptance/onion-denylist.txt",
    "illegal-content.text-pattern-path=classpath:acceptance/illegal-text-patterns.txt",
})
@Import({TestcontainersConfiguration.class, AcceptanceSpringConfiguration.Infra.class})
public class AcceptanceSpringConfiguration {

    @TestConfiguration(proxyBeanMethods = false)
    static class Infra {

        @Bean
        @ServiceConnection(name = "redis")
        GenericContainer<?> redisContainer() {
            return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        }

        @Bean
        @ServiceConnection
        Neo4jContainer neo4jContainer() {
            return new Neo4jContainer("neo4j:5-community");
        }

        @Bean
        GenericContainer<?> qdrantContainer() {
            return new GenericContainer<>("qdrant/qdrant:v1.11.0").withExposedPorts(6333);
        }

        @Bean(destroyMethod = "close")
        FakeAiProviders fakeAiProviders() {
            return new FakeAiProviders();
        }

        @Bean
        DynamicPropertyRegistrar acceptanceEndpoints(GenericContainer<?> qdrantContainer, FakeAiProviders fakes) {
            return registry -> {
                registry.add("qdrant.url", qdrantContainer::getHost);
                registry.add("qdrant.port", () -> qdrantContainer.getMappedPort(6333));
                registry.add("ai.openai-compatible.deepseek.base-url", fakes.deepseek::baseUrl);
                registry.add("ai.gemini.base-url", fakes.gemini::baseUrl);
            };
        }
    }
}
