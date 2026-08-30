package com.onionmind.ai.routing;

import com.onionmind.ai.provider.ProviderQuota;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Per-provider free-tier accounting in Redis, tracked daily AND per minute — the
 * per-minute ceiling trips first under real concurrency (master-sdd sec. 6.3). Keys
 * expire on their own; nothing to clean up.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class ProviderQuotaTracker {

    private static final Duration DAY_TTL = Duration.ofHours(26);
    private static final Duration MINUTE_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Map<String, ProviderLimits> limits;

    public ProviderQuotaTracker(StringRedisTemplate redis,
                                @Value("${ai.groq.daily-limit}") long groqDaily,
                                @Value("${ai.groq.minute-limit}") long groqMinute,
                                @Value("${ai.gemini.daily-limit}") long geminiDaily,
                                @Value("${ai.gemini.minute-limit}") long geminiMinute) {
        this(redis, Clock.systemUTC(), Map.of(
            "groq", new ProviderLimits(groqDaily, groqMinute),
            "gemini", new ProviderLimits(geminiDaily, geminiMinute)));
    }

    public ProviderQuotaTracker(StringRedisTemplate redis, Clock clock, Map<String, ProviderLimits> limits) {
        this.redis = redis;
        this.clock = clock;
        this.limits = limits;
    }

    /** Headroom for a tracked provider; {@link ProviderQuota#UNLIMITED} for anything untracked (the local provider). */
    public ProviderQuota remaining(String providerId) {
        ProviderLimits limit = limits.get(providerId);
        if (limit == null) {
            return ProviderQuota.UNLIMITED;
        }
        long dayRemaining = limit.daily() - read(dayKey(providerId));
        long minuteRemaining = limit.perMinute() - read(minuteKey(providerId));
        long effective = Math.max(0, Math.min(dayRemaining, minuteRemaining));
        return new ProviderQuota(effective, limit.daily());
    }

    /** Call after every provider request that actually went out. */
    public void recordUsage(String providerId) {
        if (!limits.containsKey(providerId)) {
            return;
        }
        bump(dayKey(providerId), DAY_TTL);
        bump(minuteKey(providerId), MINUTE_TTL);
    }

    /** On HTTP 429: park this provider for the rest of the current minute. */
    public void markExhausted(String providerId) {
        ProviderLimits limit = limits.get(providerId);
        if (limit == null) {
            return;
        }
        String key = minuteKey(providerId);
        redis.opsForValue().set(key, Long.toString(limit.perMinute()));
        redis.expire(key, MINUTE_TTL);
    }

    private long read(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key)).map(Long::parseLong).orElse(0L);
    }

    private void bump(String key, Duration ttl) {
        redis.opsForValue().increment(key);
        redis.expire(key, ttl);
    }

    private String dayKey(String providerId) {
        return "quota:%s:day:%s".formatted(providerId, LocalDate.now(clock));
    }

    private String minuteKey(String providerId) {
        return "quota:%s:minute:%d".formatted(providerId, clock.instant().getEpochSecond() / 60);
    }
}
