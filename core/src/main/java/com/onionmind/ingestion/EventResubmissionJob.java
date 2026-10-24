package com.onionmind.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redelivers this module's event publications whose listener failed (Neo4j down, a
 * transient DB error). Restart redelivery alone would leave them stuck until the next
 * deploy. Old enough that an in-flight listener is never delivered twice concurrently.
 */
@Component
public class EventResubmissionJob {

    private final IncompleteEventPublications publications;
    private final Duration olderThan;

    public EventResubmissionJob(IncompleteEventPublications publications,
                                @Value("${events.resubmit-after:10m}") Duration olderThan) {
        this.publications = publications;
        this.olderThan = olderThan;
    }

    @Scheduled(fixedDelayString = "${events.resubmit-interval-ms:300000}",
               initialDelayString = "${events.resubmit-interval-ms:300000}")
    public void run() {
        publications.resubmitIncompletePublicationsOlderThan(olderThan);
    }
}
