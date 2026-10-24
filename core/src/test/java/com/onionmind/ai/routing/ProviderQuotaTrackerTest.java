package com.onionmind.ai.routing;

import com.onionmind.ai.RedisTestSupport;
import com.onionmind.ai.provider.ProviderException.Quota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderQuotaTrackerTest {

    private final TestClock clock = new TestClock();
    private final StringRedisTemplate redis = RedisTestSupport.template();
    private ProviderQuotaTracker tracker;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        tracker = new ProviderQuotaTracker(redis, clock, Map.of(
            "gemini", new ProviderLimits(100, 5)));
    }

    @Test
    void recordUsageDecrementsRemaining() {
        assertThat(tracker.remaining("gemini").remaining()).isEqualTo(5); // per-minute is the binding limit

        tracker.recordUsage("gemini");
        tracker.recordUsage("gemini");

        assertThat(tracker.remaining("gemini").remaining()).isEqualTo(3);
    }

    @Test
    void perMinuteCeilingTripsBeforeDaily() {
        for (int i = 0; i < 5; i++) {
            tracker.recordUsage("gemini");
        }
        assertThat(tracker.remaining("gemini").remaining()).isZero();
        assertThat(tracker.remaining("gemini").availableWithMargin(5)).isFalse();
    }

    @Test
    void minuteCounterResetsOnMinuteRollover() {
        for (int i = 0; i < 5; i++) {
            tracker.recordUsage("gemini");
        }
        assertThat(tracker.remaining("gemini").remaining()).isZero();

        clock.advance(Duration.ofMinutes(1));

        // fresh minute bucket; daily still has 95 left, so per-minute cap (5) binds again
        assertThat(tracker.remaining("gemini").remaining()).isEqualTo(5);
    }

    @Test
    void dailyCounterResetsOnDayRollover() {
        for (int day = 0; day < 1; day++) {
            for (int i = 0; i < 5; i++) {
                tracker.recordUsage("gemini");
            }
            clock.advance(Duration.ofMinutes(1));
        }
        // used 5 today against a daily cap of 100
        clock.advance(Duration.ofDays(1));
        assertThat(tracker.remaining("gemini").remaining()).isEqualTo(5);
    }

    @Test
    void minuteParkLastsUntilTheMinuteRolls() {
        tracker.park("gemini", tracker.quotaReturns(Quota.MINUTE));
        assertThat(tracker.remaining("gemini").availableWithMargin(5)).isFalse();

        clock.advance(Duration.ofMinutes(1));
        assertThat(tracker.remaining("gemini").remaining()).isEqualTo(5);
    }

    @Test
    void dailyParkLastsUntilMidnightPacificNotJustTheMinute() {
        // 12:00Z = 05:00 Pacific (PDT, UTC-7): the quota day ends at 07:00Z the next day.
        assertThat(tracker.quotaReturns(Quota.DAY)).isEqualTo(Instant.parse("2026-08-31T07:00:00Z"));
        tracker.park("gemini", tracker.quotaReturns(Quota.DAY));

        clock.advance(Duration.ofHours(18));
        assertThat(tracker.remaining("gemini").remaining()).isZero();

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(tracker.remaining("gemini").remaining()).isEqualTo(5);
    }

    @Test
    void parkKeyExpiresWithTheDeadline() {
        tracker.park("gemini", clock.instant().plusSeconds(120));

        assertThat(redis.getExpire("quota:gemini:parked")).isBetween(110L, 120L);
    }

    @Test
    void balanceParkHasNoDeadlineAndEvenAnUnlimitedProviderStaysOut() {
        assertThat(tracker.quotaReturns(Quota.BALANCE)).isNull();
        tracker.park("deepseek", null);

        clock.advance(Duration.ofDays(30));
        assertThat(tracker.remaining("deepseek").availableWithMargin(5)).isFalse();
        assertThat(redis.getExpire("quota:deepseek:parked")).isEqualTo(-1L); // no TTL: a person releases it
    }

    @Test
    void restartReleasesOnlyBalanceParks() {
        tracker.park("deepseek", null);
        tracker.park("gemini", tracker.quotaReturns(Quota.DAY));

        ProviderQuotaTracker restarted = new ProviderQuotaTracker(redis, new MockEnvironment()
            .withProperty("ai.providers.gemini.requests-per-day", "100")
            .withProperty("ai.providers.gemini.requests-per-minute", "5"), List.of("gemini", "deepseek"));
        restarted.releaseBalanceParks();

        assertThat(restarted.remaining("deepseek").unlimited()).isTrue();
        assertThat(redis.opsForValue().get("quota:gemini:parked")).isNotNull(); // a daily park survives
        assertThat(restarted.trackedProviders()).containsExactly("gemini");
    }

    @Test
    void untrackedProviderIsUnlimited() {
        assertThat(tracker.remaining("deepseek").unlimited()).isTrue();
        tracker.recordUsage("deepseek");   // no-op, must not throw
    }

    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-30T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
