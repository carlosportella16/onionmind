package com.onionmind.ai.provider;

import com.onionmind.ai.FakeHttpServer;
import com.onionmind.ai.RedisTestSupport;
import com.onionmind.ai.routing.ProviderLimits;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiProviderTest {

    private final StringRedisTemplate redis = RedisTestSupport.template();
    private FakeHttpServer server;
    private ProviderQuotaTracker tracker;
    private GeminiProvider provider;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        server = new FakeHttpServer();
        tracker = new ProviderQuotaTracker(redis, Clock.systemUTC(),
            Map.of("gemini", new ProviderLimits(10000, 1000)));
        provider = new GeminiProvider(server.baseUrl(), "gemini-2.5-flash-lite", "test-key", tracker);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void sendsSystemInstructionJsonOutputAndThinkingOff() throws Exception {
        server.responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}";

        CompletionResponse response = provider.complete(new CompletionRequest("sys", "page text"));

        assertThat(server.requestPaths).containsExactly("/models/gemini-2.5-flash-lite:generateContent");
        assertThat(server.authHeaders.get(0)).isEqualTo("test-key");
        JsonNode body = JsonMapper.builder().build().readTree(server.requestBodies.get(0));
        assertThat(body.at("/systemInstruction/parts/0/text").asString()).isEqualTo("sys");
        assertThat(body.at("/contents/0/parts/0/text").asString()).isEqualTo("page text");
        assertThat(body.at("/generationConfig/responseMimeType").asString()).isEqualTo("application/json");
        assertThat(body.at("/generationConfig/thinkingConfig/thinkingBudget").asInt(-1)).isZero();
        assertThat(response.text()).isEqualTo("{}");
    }

    @Test
    void withoutSystemPromptThereIsNoSystemInstruction() {
        provider.complete(new CompletionRequest("only user"));

        assertThat(server.requestBodies.get(0)).doesNotContain("systemInstruction").contains("only user");
    }

    @Test
    void perMinute429ParksUntilTheMinuteRolls() {
        server.status = 429;
        server.responseBody = quotaFailure("GenerateRequestsPerMinutePerProjectPerModel");

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOfSatisfying(ProviderException.class,
                e -> assertThat(e.exhausted()).isEqualTo(ProviderException.Quota.MINUTE));

        assertThat(provider.currentQuota().remaining()).isZero();
        assertThat(redis.getExpire("quota:gemini:parked")).isBetween(0L, 60L);
    }

    @Test
    void daily429ParksUntilTheQuotaDayEnds() {
        server.status = 429;
        server.responseBody = quotaFailure("GenerateRequestsPerDayPerProjectPerModel");

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOfSatisfying(ProviderException.class,
                e -> assertThat(e.exhausted()).isEqualTo(ProviderException.Quota.DAY));

        assertThat(provider.currentQuota().remaining()).isZero();
        assertThat(redis.getExpire("quota:gemini:parked")).isGreaterThan(60L);
    }

    @Test
    void unparseable429FallsBackToTheMinutePark() {
        server.status = 429;
        server.responseBody = "not json";

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOfSatisfying(ProviderException.class,
                e -> assertThat(e.exhausted()).isEqualTo(ProviderException.Quota.MINUTE));
    }

    @Test
    void serverErrorIsRetryableAndDoesNotPark() {
        server.status = 503;

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOfSatisfying(ProviderException.class, e -> {
                assertThat(e.retryable()).isTrue();
                assertThat(e.rateLimited()).isFalse();
            });
        assertThat(provider.currentQuota().remaining()).isPositive();
    }

    private static String quotaFailure(String quotaId) {
        return """
            {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "details": [
              {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
               "violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_requests",
                               "quotaId": "%s"}]},
              {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "51s"}]}}
            """.formatted(quotaId);
    }
}
