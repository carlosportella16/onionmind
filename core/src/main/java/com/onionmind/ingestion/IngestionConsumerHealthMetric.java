package com.onionmind.ingestion;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.Admin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code ingestion.consumer.active_members} — active members of the {@code raw-pages}
 * consumer group. No built-in Spring Boot Kafka health indicator exists in this Spring
 * Boot version (confirmed empirically, fix-ingestion-stability) — this gauge is what makes
 * a stuck consumer visible instead. Zero members alongside a growing {@code ingestion.ai.lag}
 * means the consumer was evicted (e.g. a single record's processing exceeded
 * max.poll.interval.ms) and is stuck re-joining and re-stalling, not actually consuming —
 * exactly the state found live on 2026-09-07. A value of -1 means the admin call itself
 * failed (broker unreachable), distinct from a genuinely empty group.
 */
@Component
public class IngestionConsumerHealthMetric {

    private static final Logger log = LoggerFactory.getLogger(IngestionConsumerHealthMetric.class);
    private static final String GROUP_ID = "onionmind-ingestion";

    private final Admin admin;

    public IngestionConsumerHealthMetric(MeterRegistry registry, KafkaAdmin kafkaAdmin) {
        this.admin = Admin.create(kafkaAdmin.getConfigurationProperties());
        Gauge.builder("ingestion.consumer.active_members", this, IngestionConsumerHealthMetric::activeMembers)
            .register(registry);
    }

    private double activeMembers() {
        try {
            var description = admin.describeConsumerGroups(List.of(GROUP_ID))
                .describedGroups().get(GROUP_ID).get(3, TimeUnit.SECONDS);
            return description.members().size();
        } catch (Exception e) {
            log.warn("Could not describe consumer group '{}': {}", GROUP_ID, e.getMessage());
            return -1;
        }
    }

    @PreDestroy
    void close() {
        admin.close();
    }
}
