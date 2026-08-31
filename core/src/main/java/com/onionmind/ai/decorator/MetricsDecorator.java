package com.onionmind.ai.decorator;

import com.onionmind.ai.TaskContext.TaskType;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * Micrometer instrumentation for the orchestrator (SDD sec. 15 / fase3-sdd sec. 10):
 * request count by provider+task+outcome, a duration histogram, a confidence distribution,
 * and a remaining-quota gauge per cloud provider. Duration is in seconds — a separate
 * scale from search latency (ms).
 */
@Component
@ConditionalOnExpression("${ai.enabled:false}")
public class MetricsDecorator {

    private static final List<String> CLOUD_PROVIDERS = List.of("groq", "gemini");

    private final MeterRegistry registry;

    public MetricsDecorator(MeterRegistry registry, ProviderQuotaTracker quotaTracker) {
        this.registry = registry;
        for (String provider : CLOUD_PROVIDERS) {
            Gauge.builder("ai.quota.remaining", quotaTracker, t -> t.remaining(provider).remaining())
                .tag("provider", provider)
                .register(registry);
        }
    }

    public <T> T timed(TaskType type, String provider, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        try {
            return call.get();
        } finally {
            sample.stop(registry.timer("ai.request.duration", "task", type.name(), "provider", provider));
        }
    }

    public void recordOutcome(String provider, TaskType type, String outcome) {
        registry.counter("ai.request.total", "provider", provider, "task", type.name(), "outcome", outcome)
            .increment();
    }

    public void recordConfidence(TaskType type, double confidence) {
        registry.summary("ai.confidence", "task", type.name()).record(confidence);
    }
}
