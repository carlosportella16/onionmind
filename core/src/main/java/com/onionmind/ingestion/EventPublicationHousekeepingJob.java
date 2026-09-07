package com.onionmind.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Purges completed publications from the Spring Modulith Event Publication Registry
 * (ADR-009, "Housekeeping" — the table grows without bound otherwise). Delegates to
 * {@link EventPublicationRegistry#deleteCompletedPublicationsOlderThan}, the registry's own
 * supported operation, instead of a hand-rolled DELETE — it only ever touches completed rows;
 * anything still incomplete (including a publication a crashed instance never got to) is
 * left alone regardless of age.
 */
@Component
public class EventPublicationHousekeepingJob {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationHousekeepingJob.class);

    private final EventPublicationRegistry registry;
    private final JdbcTemplate jdbc;
    private final Duration retention;

    public EventPublicationHousekeepingJob(EventPublicationRegistry registry,
                                            JdbcTemplate jdbc,
                                            @Value("${event-publication.retention-days:7}") int retentionDays) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(fixedDelayString = "${event-publication.purge-interval-ms:3600000}")
    public void run() {
        Long before = countRows();
        registry.deleteCompletedPublicationsOlderThan(retention);
        Long after = countRows();
        if (before != null && after != null && after < before) {
            log.info("Event Publication Registry housekeeping: purged {} completed publication(s)", before - after);
        }
    }

    private Long countRows() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication", Long.class);
    }
}
