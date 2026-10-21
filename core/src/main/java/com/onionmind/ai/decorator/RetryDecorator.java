package com.onionmind.ai.decorator;

import com.onionmind.ai.provider.ProviderException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Same-provider retry for transient transport failures only (timeout, 5xx). A 429
 * ({@code rateLimited}) or a non-retryable 4xx is rethrown immediately — switching
 * provider is the escalation loop's job, not a retry (SDD sec. 7.2).
 */
@Component
@ConditionalOnExpression("${ai.enabled:false}")
public class RetryDecorator {

    private final int maxRetries;
    private final Duration backoff;

    public RetryDecorator(@Value("${ai.retry.max-retries:2}") int maxRetries,
                          @Value("${ai.retry.backoff:200ms}") Duration backoff) {
        this.maxRetries = maxRetries;
        this.backoff = backoff;
    }

    public <T> T execute(Supplier<T> call) {
        ProviderException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return call.get();
            } catch (ProviderException e) {
                last = e;
                if (!e.retryable() || e.rateLimited() || attempt == maxRetries) {
                    throw e;
                }
                sleep(backoff.multipliedBy(attempt + 1L));
            }
        }
        throw last; // unreachable: the loop either returns or throws
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("retry interrupted", e);
        }
    }
}
