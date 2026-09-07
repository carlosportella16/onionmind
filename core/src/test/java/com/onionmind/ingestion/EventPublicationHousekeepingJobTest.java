package com.onionmind.ingestion;

import com.onionmind.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRegistry;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EventPublicationHousekeepingJobTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EventPublicationRegistry registry;

    private EventPublicationHousekeepingJob job(int retentionDays) {
        return new EventPublicationHousekeepingJob(registry, jdbc, retentionDays);
    }

    private void insertPublication(String status, String completionDateExpr) {
        jdbc.update("""
            INSERT INTO event_publication
                (id, listener_id, event_type, serialized_event, publication_date, completion_date, status)
            VALUES (?, 'listener', 'SomeEvent', '{}', now(), %s, ?)
            """.formatted(completionDateExpr), UUID.randomUUID(), status);
    }

    private int countRows() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void purgesCompletedPublicationsOlderThanRetentionWindow() {
        jdbc.update("DELETE FROM event_publication");
        insertPublication("COMPLETED", "now() - interval '30 days'");
        insertPublication("COMPLETED", "now() - interval '1 day'");
        insertPublication(null, "null"); // still pending — never purged regardless of age

        job(7).run();

        assertThat(countRows()).isEqualTo(2); // only the 30-day-old completed row is gone
    }

    @Test
    void neverPurgesPendingPublicationsRegardlessOfAge() {
        jdbc.update("DELETE FROM event_publication");
        insertPublication(null, "null");

        job(0).run();

        assertThat(countRows()).isEqualTo(1);
    }
}
