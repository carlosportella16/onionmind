package com.onionmind.ingestion;

import com.onionmind.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IngestionAiLagMetricTest {

    @Autowired
    private JdbcTemplate jdbc;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM page_diffs");
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
    }

    private void insert(String url, String status) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version, ai_status)
            VALUES (?, 'tor', 'body', 'h', 1, ?)
            """, url, status);
    }

    @Test
    void gaugeCountsPagesWaitingForEnrichment() {
        insert("http://a.onion", "pending");
        insert("http://b.onion", "failed_transient");
        insert("http://c.onion", "processed");

        new IngestionAiLagMetric(registry, jdbc);

        assertThat(registry.get("ingestion.ai.lag").gauge().value()).isEqualTo(2.0);
    }
}
