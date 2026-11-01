package com.onionmind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void v8AddsAiAttemptsWithZeroDefault() {
        var column = jdbcTemplate.queryForMap("""
                SELECT is_nullable, column_default FROM information_schema.columns
                WHERE table_name = 'pages' AND column_name = 'ai_attempts'
                """);
        assertThat(column).containsEntry("is_nullable", "NO").containsEntry("column_default", "0");
    }

    @Test
    void v10AddsAgentsRunsTheDailyIndexAndTheResearchAgentSeed() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('agents', 'agent_runs')",
                Integer.class);
        assertThat(tables).isEqualTo(2);

        String dailyIndex = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_agent_runs_daily'", String.class);
        assertThat(dailyIndex).startsWith("CREATE UNIQUE INDEX").contains("WHERE ((trigger)::text = 'daily'::text)");

        var seed = jdbcTemplate.queryForMap("""
                SELECT array_to_string(tools, ',') AS tools, cost_profile, daily
                FROM agents WHERE name = 'Research Agent'
                """);
        assertThat(seed).containsEntry("tools", "search,changes,entities")
                .containsEntry("cost_profile", "batch").containsEntry("daily", false);

        long agent = jdbcTemplate.queryForObject("SELECT id FROM agents WHERE name = 'Research Agent'", Long.class);
        jdbcTemplate.update("INSERT INTO agent_runs (agent_id, trigger, status) VALUES (?, 'daily', 'running')", agent);
        try {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO agent_runs (agent_id, trigger, status) VALUES (?, 'daily', 'running')", agent))
                    .isInstanceOf(DuplicateKeyException.class);
            // manual runs are not limited
            jdbcTemplate.update("INSERT INTO agent_runs (agent_id, trigger, status) VALUES (?, 'manual', 'running')", agent);
            jdbcTemplate.update("INSERT INTO agent_runs (agent_id, trigger, status) VALUES (?, 'manual', 'running')", agent);
        } finally {
            jdbcTemplate.update("DELETE FROM agent_runs WHERE agent_id = ?", agent);
        }
    }

    @Test
    void v9DropsThePageTranslationColumn() {
        Integer translationColumn = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'pages' AND column_name = 'translated_text'
                """, Integer.class);
        assertThat(translationColumn).isZero();
    }
}
