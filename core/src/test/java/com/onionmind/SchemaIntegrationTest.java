package com.onionmind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void pagesTableExistsAfterFlywayMigration() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'pages'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void v4AddsAiGenerationSchema() {
        Integer aiColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'pages'
                  AND column_name IN ('ai_status', 'ai_processed_at', 'ai_error_message')
                """, Integer.class);
        assertThat(aiColumns).isEqualTo(3);

        Integer quarantineTable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'quarantined_pages'",
                Integer.class);
        assertThat(quarantineTable).isEqualTo(1);
    }
}
