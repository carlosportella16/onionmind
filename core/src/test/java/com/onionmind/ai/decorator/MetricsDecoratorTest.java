package com.onionmind.ai.decorator;

import com.onionmind.ai.TaskContext.TaskType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsDecoratorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsDecorator metrics = new MetricsDecorator(registry);

    @Test
    void timedRecordsDurationByTask() {
        String out = metrics.timed(TaskType.SUMMARIZE, () -> "done");

        assertThat(out).isEqualTo("done");
        assertThat(registry.get("ai.request.duration").tag("task", "SUMMARIZE").timer().count()).isEqualTo(1);
    }

    @Test
    void recordOutcomeIncrementsCounterWithTags() {
        metrics.recordOutcome("groq", TaskType.CLASSIFY, "ok");
        metrics.recordOutcome("groq", TaskType.CLASSIFY, "ok");

        assertThat(registry.get("ai.request.total")
            .tags("provider", "groq", "task", "CLASSIFY", "outcome", "ok")
            .counter().count()).isEqualTo(2.0);
    }

    @Test
    void recordConfidenceFeedsDistribution() {
        metrics.recordConfidence(TaskType.TRANSLATE, 0.8);

        assertThat(registry.get("ai.confidence").tag("task", "TRANSLATE").summary().count()).isEqualTo(1);
    }
}
