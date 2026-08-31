package com.onionmind.ai.decorator;

import com.onionmind.ai.TaskContext.TaskType;
import com.onionmind.ai.provider.ProviderQuota;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsDecoratorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProviderQuotaTracker tracker = mock(ProviderQuotaTracker.class);
    private final MetricsDecorator metrics = new MetricsDecorator(registry, tracker);

    @Test
    void timedRecordsDurationByTaskAndProvider() {
        String out = metrics.timed(TaskType.SUMMARIZE, "groq", () -> "done");

        assertThat(out).isEqualTo("done");
        assertThat(registry.get("ai.request.duration")
            .tags("task", "SUMMARIZE", "provider", "groq").timer().count()).isEqualTo(1);
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

    @Test
    void quotaRemainingIsExposedAsAGaugePerCloudProvider() {
        when(tracker.remaining(any())).thenReturn(new ProviderQuota(1234, 14400));

        assertThat(registry.get("ai.quota.remaining").tag("provider", "groq").gauge().value()).isEqualTo(1234);
        assertThat(registry.get("ai.quota.remaining").tag("provider", "gemini").gauge().value()).isEqualTo(1234);
    }
}
