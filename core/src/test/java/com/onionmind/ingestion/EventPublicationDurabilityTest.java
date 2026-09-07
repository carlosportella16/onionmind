package com.onionmind.ingestion;

import com.onionmind.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * ADR-009 durability: a publication whose listener never completes stays in the registry as
 * incomplete — exactly what {@code republish-outstanding-events-on-restart} resubmits on the
 * next boot. Simulating an actual JVM crash mid-publication isn't practical in a test process;
 * this proves the mechanism that restart hook depends on actually persists unfinished work
 * instead of silently dropping it.
 */
@Import({TestcontainersConfiguration.class, EventPublicationDurabilityTest.FailingListenerConfig.class})
@SpringBootTest
class EventPublicationDurabilityTest {

    @Autowired
    private FailingListenerConfig.Publisher committingPublisher;

    @Autowired
    private EventPublicationRegistry registry;

    @Autowired
    private FailingListenerConfig.Tracker tracker;

    @Test
    void publicationNeverCompletedByAFailingListenerStaysIncompleteForResubmission() {
        committingPublisher.publishAndCommit(new FailingListenerConfig.AlwaysFailsEvent("durability-test"));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(tracker.attempts()).isGreaterThan(0));

        boolean stillIncomplete = registry.findIncompletePublications().stream()
            .anyMatch(p -> p.getEvent() instanceof FailingListenerConfig.AlwaysFailsEvent event
                && event.marker().equals("durability-test"));
        assertThat(stillIncomplete)
            .as("a publication whose listener threw must remain incomplete, ready for resubmission on restart")
            .isTrue();
    }

    @TestConfiguration
    static class FailingListenerConfig {

        record AlwaysFailsEvent(String marker) {
        }

        @Component
        static class Tracker {
            private final AtomicInteger attempts = new AtomicInteger();

            void recordAttempt() {
                attempts.incrementAndGet();
            }

            int attempts() {
                return attempts.get();
            }
        }

        /** Publishes inside a transaction that actually commits — the AFTER_COMMIT listener never fires otherwise. */
        @Component
        static class Publisher {
            private final ApplicationEventPublisher events;

            Publisher(ApplicationEventPublisher events) {
                this.events = events;
            }

            @Transactional
            void publishAndCommit(AlwaysFailsEvent event) {
                events.publishEvent(event);
            }
        }

        @Component
        static class FailingListener {
            private final Tracker tracker;

            FailingListener(Tracker tracker) {
                this.tracker = tracker;
            }

            @ApplicationModuleListener
            void on(AlwaysFailsEvent event) {
                tracker.recordAttempt();
                throw new IllegalStateException("simulated failure — publication must stay incomplete");
            }
        }
    }
}
