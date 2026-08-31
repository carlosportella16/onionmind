package com.onionmind.ingestion;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code ingestion.ai.lag} — how many pages are still waiting for AI enrichment
 * (fase3-sdd sec. 10). A growing value means the backfill job is falling behind.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class IngestionAiLagMetric {

    public IngestionAiLagMetric(MeterRegistry registry, JdbcTemplate jdbc) {
        Gauge.builder("ingestion.ai.lag", jdbc, IngestionAiLagMetric::pendingCount).register(registry);
    }

    private static double pendingCount(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject(
            "SELECT count(*) FROM pages WHERE ai_status IN ('pending', 'failed_transient')", Long.class);
        return count == null ? 0 : count;
    }
}
