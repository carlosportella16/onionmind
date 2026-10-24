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
 * One Spring context for every acceptance scenario (design D3): Fases 1-4 all switched on,
 * real Postgres/Kafka/Redis/Qdrant/Neo4j, and only the model endpoints faked (D4). Backfill
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
    "ai.openai-compatible.deepseek.api-key=acceptance-test-key",
    "ai.gemini.api-key=acceptance-test-key",
    "embedding.dimensions=" + FakeAiProviders.DIMENSIONS,
    // ~100 chars per chunk, so a page with a few hundred chars spans several chunks
    "embedding.chunk-size=25",
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
