package com.onionmind.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Only wired when {@code graph.enabled=true} — every other test/profile in this project boots
 * the full Spring context without a Neo4j instance around, so this must never be eager or
 * unconditional (matches the {@code ai.enabled}/{@code embedding.enabled} pattern already used
 * for the other two optional subsystems).
 */
@Configuration
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
class Neo4jConfig {

    @Bean(destroyMethod = "close")
    Driver neo4jDriver(@Value("${neo4j.uri}") String uri,
                        @Value("${neo4j.username}") String username,
                        @Value("${neo4j.password}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password));
    }
}
