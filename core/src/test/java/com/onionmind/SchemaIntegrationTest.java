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
    void v6CreatesEventPublicationRegistry() {
        Integer table = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'event_publication'",
                Integer.class);
        assertThat(table).isEqualTo(1);

        // Exact column set the Spring Modulith JDBC event repository queries against (note P2-5).
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'event_publication'
                  AND column_name IN ('id', 'listener_id', 'event_type', 'serialized_event',
                                      'publication_date', 'completion_date', 'status',
                                      'completion_attempts', 'last_resubmission_date')
                """, Integer.class);
        assertThat(columns).isEqualTo(9);
    }
}
