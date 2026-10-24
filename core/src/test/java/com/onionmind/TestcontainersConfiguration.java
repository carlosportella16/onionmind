package com.onionmind;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Every Spring test context starts its own containers, so a suite run starts about ten of each.
 * kafka-native now and then exits with code 1 while starting (seen on CI and locally), so each
 * container gets a few attempts instead of failing the whole context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final int STARTUP_ATTEMPTS = 3;

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"))
			.withStartupAttempts(STARTUP_ATTEMPTS);
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:latest"))
			.withStartupAttempts(STARTUP_ATTEMPTS);
	}

}
