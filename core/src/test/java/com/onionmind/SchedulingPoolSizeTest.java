package com.onionmind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix-large-page-backfill-limits: Spring Boot defaults spring.task.scheduling.pool.size to
 * 1 — every @Scheduled bean in the app shared one thread. A single long EmbeddingBackfillJob
 * run (observed: 13+ minutes on a large page) starved AiEnrichmentBackfillJob completely,
 * which never got a turn on its own independent 5-minute schedule. Proven here against the
 * shared TaskScheduler infrastructure directly rather than the real jobs, to stay fast and
 * deterministic (no dependency on AI provider/Qdrant timing).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchedulingPoolSizeTest {

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    void everyScheduledJobGetsItsOwnThread() throws InterruptedException {
        // AiEnrichmentBackfillJob, EmbeddingBackfillJob, ChangeSummaryJob, EventResubmissionJob.
        int jobs = 4;
        CountDownLatch allStarted = new CountDownLatch(jobs);
        CountDownLatch release = new CountDownLatch(1);

        for (int i = 0; i < jobs; i++) {
            taskScheduler.schedule(() -> {
                allStarted.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }, Instant.now());
        }

        boolean noneQueuedBehindAnother = allStarted.await(1, TimeUnit.SECONDS);
        release.countDown();

        assertThat(noneQueuedBehindAnother)
            .as("%d slow scheduled jobs must all be running at once", jobs)
            .isTrue();
    }
}
