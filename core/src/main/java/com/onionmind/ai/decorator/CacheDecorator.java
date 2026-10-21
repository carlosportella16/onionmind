package com.onionmind.ai.decorator;

import com.onionmind.ai.TaskContext.TaskType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Redis cache of validated AI results, keyed by {@code (task type, sha256(text))} — not by
 * provider (D7/D8): any provider's answer for the same text and task is interchangeable.
 * Covers job restarts and overlapping backfill runs.
 */
@Component
@ConditionalOnExpression("${ai.enabled:false}")
public class CacheDecorator {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Duration ttl;

    public CacheDecorator(StringRedisTemplate redis, @Value("${ai.cache-ttl:24h}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    public Optional<ValidatedResult> get(TaskType type, String text) {
        String cached = redis.opsForValue().get(key(type, text));
        if (cached == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(cached, ValidatedResult.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void put(TaskType type, String text, ValidatedResult result) {
        try {
            redis.opsForValue().set(key(type, text), mapper.writeValueAsString(result), ttl);
        } catch (Exception e) {
            // cache write failure must never fail the task
        }
    }

    private String key(TaskType type, String text) {
        return "ai-cache:%s:%s".formatted(type, sha256(text));
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
