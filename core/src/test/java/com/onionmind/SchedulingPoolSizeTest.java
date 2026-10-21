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
 * deterministic (no dependency on Ollama/Qdrant timing).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SchedulingPoolSizeTest {

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    void aSecondScheduledTaskRunsWithoutWaitingForALongFirstOneToFinish() throws InterruptedException {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        taskScheduler.schedule(() -> {
            bothStarted.countDown();
            try {
                releaseFirst.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        }, Instant.now());

        taskScheduler.schedule(bothStarted::countDown, Instant.now());

        boolean secondTaskDidNotQueueBehindTheFirst = bothStarted.await(1, TimeUnit.SECONDS);
        releaseFirst.countDown();

        assertThat(secondTaskDidNotQueueBehindTheFirst)
            .as("a second scheduled task must not have to wait for a slow first one to finish")
            .isTrue();
    }
}
