package com.onionmind.ai.decorator;

import com.onionmind.ai.TaskContext.TaskType;
import com.onionmind.ai.provider.ProviderQuota;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsDecoratorTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProviderQuotaTracker tracker = trackerOf("gemini");
    private final MetricsDecorator metrics = new MetricsDecorator(registry, tracker);

    private static ProviderQuotaTracker trackerOf(String... tracked) {
        ProviderQuotaTracker tracker = mock(ProviderQuotaTracker.class);
        when(tracker.trackedProviders()).thenReturn(Set.of(tracked));
        return tracker;
    }

    @Test
    void timedRecordsDurationByTaskAndProvider() {
        String out = metrics.timed(TaskType.SUMMARIZE, "deepseek", () -> "done");

        assertThat(out).isEqualTo("done");
        assertThat(registry.get("ai.request.duration")
            .tags("task", "SUMMARIZE", "provider", "deepseek").timer().count()).isEqualTo(1);
    }

    @Test
    void recordOutcomeIncrementsCounterWithTags() {
        metrics.recordOutcome("deepseek", TaskType.CLASSIFY, "ok");
        metrics.recordOutcome("deepseek", TaskType.CLASSIFY, "ok");

        assertThat(registry.get("ai.request.total")
            .tags("provider", "deepseek", "task", "CLASSIFY", "outcome", "ok")
            .counter().count()).isEqualTo(2.0);
    }

    @Test
    void recordConfidenceFeedsDistribution() {
        metrics.recordConfidence(TaskType.TRANSLATE, 0.8);

        assertThat(registry.get("ai.confidence").tag("task", "TRANSLATE").summary().count()).isEqualTo(1);
    }

    @Test
    void quotaRemainingIsExposedOnlyForProvidersWithConfiguredLimits() {
        when(tracker.remaining(any())).thenReturn(new ProviderQuota(1234, 10000));

        assertThat(registry.get("ai.quota.remaining").tag("provider", "gemini").gauge().value()).isEqualTo(1234);
        assertThat(registry.find("ai.quota.remaining").tag("provider", "deepseek").gauge()).isNull();
    }
}
