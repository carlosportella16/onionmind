package com.onionmind.ai.routing;

import com.onionmind.ai.provider.ProviderException;
import com.onionmind.ai.provider.ProviderQuota;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-provider request accounting in Redis, tracked daily AND per minute — the per-minute
 * ceiling trips first under real concurrency (master-sdd sec. 6.3). Limits come from
 * {@code ai.providers.<id>}; a provider without an entry is never gated by quota, only by
 * {@link #park}. Keys expire on their own; nothing to clean up.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class ProviderQuotaTracker {

    private static final Logger log = LoggerFactory.getLogger(ProviderQuotaTracker.class);
    private static final Duration DAY_TTL = Duration.ofHours(26);
    private static final Duration MINUTE_TTL = Duration.ofSeconds(90);
    // Gemini's requests-per-day reset at midnight Pacific, not UTC (ai.google.dev rate-limits, 2026-10-15).
    private static final ZoneId QUOTA_DAY_ZONE = ZoneId.of("America/Los_Angeles");
    private static final String UNTIL_RELEASED = "until-released";
    private static final ProviderQuota PARKED = new ProviderQuota(0, 0);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Map<String, ProviderLimits> limits;
    private final List<String> ladder;

    @Autowired
    public ProviderQuotaTracker(StringRedisTemplate redis, Environment env,
                                @Value("${ai.ladder:}") List<String> ladder) {
        this(redis, Clock.systemUTC(), Binder.get(env)
            .bind("ai.providers", Bindable.mapOf(String.class, ProviderLimits.class))
            .orElse(Map.of()), ladder);
    }

    public ProviderQuotaTracker(StringRedisTemplate redis, Clock clock, Map<String, ProviderLimits> limits) {
        this(redis, clock, limits, List.of());
    }

    private ProviderQuotaTracker(StringRedisTemplate redis, Clock clock, Map<String, ProviderLimits> limits,
                                 List<String> ladder) {
        this.redis = redis;
        this.clock = clock;
        this.limits = limits;
        this.ladder = ladder;
    }

    /** A balance park (402) lasts until a restart: that is the "someone topped up" signal (D5). */
    @PostConstruct
    void releaseBalanceParks() {
        try {
            for (String id : ladder) {
                if (UNTIL_RELEASED.equals(redis.opsForValue().get(parkedKey(id)))) {
                    redis.delete(parkedKey(id));
                }
            }
        } catch (Exception e) {
            log.warn("Could not release balance-parked AI providers on startup: {}", e.getMessage());
        }
    }

    /** Providers with configured limits — the ones a remaining-quota number means something for. */
    public Set<String> trackedProviders() {
        return limits.keySet();
    }

    /**
     * Headroom for a provider: zero while parked, {@link ProviderQuota#UNLIMITED} for one
     * without configured limits.
     */
    public ProviderQuota remaining(String providerId) {
        if (parked(providerId)) {
            return PARKED;
        }
        ProviderLimits limit = limits.get(providerId);
        if (limit == null) {
            return ProviderQuota.UNLIMITED;
        }
        long dayRemaining = limit.requestsPerDay() - read(dayKey(providerId));
        long minuteRemaining = limit.requestsPerMinute() - read(minuteKey(providerId));
        // Pair remaining with whichever limit is actually binding — margin-percent must be
        // read against that same limit, or a tight per-minute cap (e.g. 30) never clears a
        // margin computed off the much larger daily cap (e.g. 5% of 14400 = 720) and the
        // cloud provider is silently excluded from the ladder forever, even at full quota.
        return minuteRemaining <= dayRemaining
            ? new ProviderQuota(Math.max(0, minuteRemaining), limit.requestsPerMinute())
            : new ProviderQuota(Math.max(0, dayRemaining), limit.requestsPerDay());
    }

    /** Call after every provider request that actually went out. */
    public void recordUsage(String providerId) {
        if (!limits.containsKey(providerId)) {
            return;
        }
        bump(dayKey(providerId), DAY_TTL);
        bump(minuteKey(providerId), MINUTE_TTL);
    }

    /**
     * Takes the provider out of the ladder until {@code until}; {@code null} means until a
     * restart or a manual {@code DEL quota:<id>:parked} (out of balance, D5). The value holds
     * the deadline so the check follows {@link #clock}; the TTL only cleans up.
     */
    public void park(String providerId, Instant until) {
        String key = parkedKey(providerId);
        if (until == null) {
            redis.opsForValue().set(key, UNTIL_RELEASED);
            log.warn("AI provider {} is out of balance: parked until restart or `DEL {}`", providerId, key);
            return;
        }
        Duration ttl = Duration.between(clock.instant(), until);
        if (ttl.isPositive()) {
            redis.opsForValue().set(key, until.toString(), ttl);
        }
    }

    /** When quota comes back after hitting {@code quota}: next minute, next quota day, or never (402). */
    public Instant quotaReturns(ProviderException.Quota quota) {
        Instant now = clock.instant();
        return switch (quota) {
            case MINUTE -> Instant.ofEpochSecond((now.getEpochSecond() / 60 + 1) * 60);
            case DAY -> LocalDate.ofInstant(now, QUOTA_DAY_ZONE).plusDays(1).atStartOfDay(QUOTA_DAY_ZONE).toInstant();
            case BALANCE -> null;
        };
    }

    private boolean parked(String providerId) {
        String value = redis.opsForValue().get(parkedKey(providerId));
        return value != null && (UNTIL_RELEASED.equals(value) || Instant.parse(value).isAfter(clock.instant()));
    }

    private long read(String key) {
        return Optional.ofNullable(redis.opsForValue().get(key)).map(Long::parseLong).orElse(0L);
    }

    private void bump(String key, Duration ttl) {
        redis.opsForValue().increment(key);
        redis.expire(key, ttl);
    }

    private static String parkedKey(String providerId) {
        return "quota:%s:parked".formatted(providerId);
    }

    private String dayKey(String providerId) {
        return "quota:%s:day:%s".formatted(providerId, LocalDate.now(clock));
    }

    private String minuteKey(String providerId) {
        return "quota:%s:minute:%d".formatted(providerId, clock.instant().getEpochSecond() / 60);
    }
}
