package com.onionmind.graph;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One shared Neo4j container for the graph test suite, same shape as {@code RedisTestSupport}.
 * Plain {@link GenericContainer} — {@code org.testcontainers:neo4j} has no Testcontainers-2.x
 * release yet, so a dedicated container class isn't an option here.
 */
final class Neo4jTestSupport {

    private static final GenericContainer<?> NEO4J =
        new GenericContainer<>(DockerImageName.parse("neo4j:5-community"))
            .withEnv("NEO4J_AUTH", "neo4j/" + password())
            .withExposedPorts(7687);

    private static final Driver DRIVER;

    static {
        NEO4J.start();
        DRIVER = GraphDatabase.driver(boltUri(), AuthTokens.basic("neo4j", password()));
    }

    private Neo4jTestSupport() {
    }

    static Driver driver() {
        return DRIVER;
    }

    static String boltUri() {
        return "bolt://" + NEO4J.getHost() + ":" + NEO4J.getMappedPort(7687);
    }

    static String password() {
        return "onionmind_test_only";
    }

    static void purgeAll() {
        try (var session = DRIVER.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }
}
