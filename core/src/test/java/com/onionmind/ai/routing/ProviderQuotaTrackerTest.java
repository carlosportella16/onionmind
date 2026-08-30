package com.onionmind.ai.routing;

import com.onionmind.ai.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderQuotaTrackerTest {

    private final TestClock clock = new TestClock();
    private ProviderQuotaTracker tracker;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        tracker = new ProviderQuotaTracker(RedisTestSupport.template(), clock, Map.of(
            "groq", new ProviderLimits(100, 5)));
    }

    @Test
    void recordUsageDecrementsRemaining() {
        assertThat(tracker.remaining("groq").remaining()).isEqualTo(5); // per-minute is the binding limit

        tracker.recordUsage("groq");
        tracker.recordUsage("groq");

        assertThat(tracker.remaining("groq").remaining()).isEqualTo(3);
    }

    @Test
    void perMinuteCeilingTripsBeforeDaily() {
        for (int i = 0; i < 5; i++) {
            tracker.recordUsage("groq");
        }
        assertThat(tracker.remaining("groq").remaining()).isZero();
        assertThat(tracker.remaining("groq").availableWithMargin(5)).isFalse();
    }

    @Test
    void minuteCounterResetsOnMinuteRollover() {
        for (int i = 0; i < 5; i++) {
            tracker.recordUsage("groq");
        }
        assertThat(tracker.remaining("groq").remaining()).isZero();

        clock.advance(Duration.ofMinutes(1));

        // fresh minute bucket; daily still has 95 left, so per-minute cap (5) binds again
        assertThat(tracker.remaining("groq").remaining()).isEqualTo(5);
    }

    @Test
    void dailyCounterResetsOnDayRollover() {
        for (int day = 0; day < 1; day++) {
            for (int i = 0; i < 5; i++) {
                tracker.recordUsage("groq");
            }
            clock.advance(Duration.ofMinutes(1));
        }
        // used 5 today against a daily cap of 100
        clock.advance(Duration.ofDays(1));
        assertThat(tracker.remaining("groq").remaining()).isEqualTo(5);
    }

    @Test
    void markExhaustedZeroesRemainingUntilMinuteRolls() {
        tracker.markExhausted("groq");
        assertThat(tracker.remaining("groq").remaining()).isZero();

        clock.advance(Duration.ofMinutes(1));
        assertThat(tracker.remaining("groq").remaining()).isEqualTo(5);
    }

    @Test
    void untrackedProviderIsUnlimited() {
        assertThat(tracker.remaining("ollama").unlimited()).isTrue();
        tracker.recordUsage("ollama");   // no-op, must not throw
        tracker.markExhausted("ollama"); // no-op, must not throw
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
