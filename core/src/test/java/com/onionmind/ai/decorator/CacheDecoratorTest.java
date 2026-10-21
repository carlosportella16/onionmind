package com.onionmind.ai.decorator;

import com.onionmind.ai.RedisTestSupport;
import com.onionmind.ai.TaskContext.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CacheDecoratorTest {

    private CacheDecorator cache;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        cache = new CacheDecorator(RedisTestSupport.template(), Duration.ofHours(24));
    }

    @Test
    void roundTripsAValidatedResult() {
        cache.put(TaskType.SUMMARIZE, "algum texto", new ValidatedResult("resumo", null, 0.9));

        assertThat(cache.get(TaskType.SUMMARIZE, "algum texto"))
            .get()
            .isEqualTo(new ValidatedResult("resumo", null, 0.9));
    }

    @Test
    void missReturnsEmpty() {
        assertThat(cache.get(TaskType.CLASSIFY, "nunca visto")).isEmpty();
    }

    @Test
    void keyIsScopedByTaskType() {
        cache.put(TaskType.SUMMARIZE, "mesmo texto", new ValidatedResult("s", null, 0.9));

        assertThat(cache.get(TaskType.CLASSIFY, "mesmo texto")).isEmpty();
        assertThat(cache.get(TaskType.SUMMARIZE, "mesmo texto")).isPresent();
    }
}
