package com.onionmind.ai.decorator;

import com.onionmind.ai.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryDecoratorTest {

    private final RetryDecorator retry = new RetryDecorator(2, Duration.ofMillis(1));

    @Test
    void retriesTransientFailureThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = retry.execute(() -> {
            if (calls.getAndIncrement() < 2) {
                throw new ProviderException("timeout", true, false);
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryNonRetryableFailure() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new ProviderException("bad request", false, false);
        })).isInstanceOf(ProviderException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRetryRateLimited() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new ProviderException("429", true, true);
        })).isInstanceOf(ProviderException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void givesUpAfterMaxRetries() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new ProviderException("timeout", true, false);
        })).isInstanceOf(ProviderException.class);

        assertThat(calls).hasValue(3); // initial + 2 retries
    }
}
