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

    @Test
    void v7AddsVersionSnapshotsAndPageChanges() {
        Integer snapshotColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'page_versions' AND column_name IN ('entities', 'category', 'summary')
                """, Integer.class);
        assertThat(snapshotColumns).isEqualTo(3);

        Integer changesTable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'page_changes'",
                Integer.class);
        assertThat(changesTable).isEqualTo(1);

        Integer uniqueIndexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                WHERE indexname IN ('idx_page_versions_page_version', 'idx_page_changes_page_to_version')
                  AND indexdef LIKE 'CREATE UNIQUE INDEX%'
                """, Integer.class);
        assertThat(uniqueIndexes).isEqualTo(2);
    }
}
