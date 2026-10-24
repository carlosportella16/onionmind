package com.onionmind.ai.decorator;

import com.onionmind.ai.RedisTestSupport;
import com.onionmind.ai.TaskContext.TaskType;
import com.onionmind.ai.provider.CompletionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class CacheDecoratorTest {

    private static final CompletionRequest SUMMARY = new CompletionRequest("summarize prompt", "algum texto");

    private CacheDecorator cache;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        cache = new CacheDecorator(RedisTestSupport.template(), Duration.ofHours(24));
    }

    @Test
    void roundTripsAValidatedResult() {
        cache.put(TaskType.SUMMARIZE, SUMMARY, new ValidatedResult("resumo", null, 0.9));

        assertThat(cache.get(TaskType.SUMMARIZE, new CompletionRequest("summarize prompt", "algum texto")))
            .get()
            .isEqualTo(new ValidatedResult("resumo", null, 0.9));
    }

    @Test
    void missReturnsEmpty() {
        assertThat(cache.get(TaskType.CLASSIFY, new CompletionRequest("classify prompt", "nunca visto"))).isEmpty();
    }

    @Test
    void keyIsScopedByTaskType() {
        cache.put(TaskType.SUMMARIZE, SUMMARY, new ValidatedResult("s", null, 0.9));

        assertThat(cache.get(TaskType.CLASSIFY, SUMMARY)).isEmpty();
        assertThat(cache.get(TaskType.SUMMARIZE, SUMMARY)).isPresent();
    }

    @Test
    void changedPromptDoesNotReuseTheOldAnswer() {
        cache.put(TaskType.SUMMARIZE, new CompletionRequest("old prompt", "mesmo texto"),
            new ValidatedResult("resumo antigo", null, 0.9));

        assertThat(cache.get(TaskType.SUMMARIZE, new CompletionRequest("new prompt", "mesmo texto"))).isEmpty();
    }

    @Test
    void entryInTheOldKeyFormatIsNotRead() throws Exception {
        String oldKey = "ai-cache:SUMMARIZE:" + HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest("algum texto".getBytes(StandardCharsets.UTF_8)));
        RedisTestSupport.template().opsForValue()
            .set(oldKey, "{\"primary\":\"resumo antigo\",\"secondary\":null,\"confidence\":0.9}");

        assertThat(cache.get(TaskType.SUMMARIZE, SUMMARY)).isEmpty();
    }
}
