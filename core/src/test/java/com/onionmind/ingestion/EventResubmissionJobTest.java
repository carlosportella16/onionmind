package com.onionmind.ingestion;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.IncompleteEventPublications;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EventResubmissionJobTest {

    @Test
    void resubmitsPublicationsOlderThanTheConfiguredAge() {
        IncompleteEventPublications publications = mock(IncompleteEventPublications.class);

        new EventResubmissionJob(publications, Duration.ofMinutes(10)).run();

        verify(publications).resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(10));
    }
}
