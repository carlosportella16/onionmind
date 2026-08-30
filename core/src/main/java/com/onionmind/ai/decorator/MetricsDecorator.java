package com.onionmind.ai.decorator;

import com.onionmind.ai.TaskContext.TaskType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Micrometer instrumentation for the orchestrator (SDD sec. 15 / fase3-sdd sec. 10):
 * request count by provider+task+outcome, duration histogram, and a confidence
 * distribution. Duration is in seconds — a separate scale from search latency (ms).
 */
@Component
@ConditionalOnExpression("${ai.enabled:false}")
public class MetricsDecorator {

    private final MeterRegistry registry;

    public MetricsDecorator(MeterRegistry registry) {
        this.registry = registry;
    }

    public <T> T timed(TaskType type, Supplier<T> call) {
        Timer.Sample sample = Timer.start(registry);
        try {
            return call.get();
        } finally {
            sample.stop(registry.timer("ai.request.duration", "task", type.name()));
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
