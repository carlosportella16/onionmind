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
            Map.of("gemini", new ProviderLimits(10000, 1000, 0, 0)));
        provider = new GeminiProvider(server.baseUrl(), "gemini-3.5-flash-lite", "test-key", tracker);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void sendsSystemInstructionJsonOutputAndMinimalThinking() throws Exception {
        server.responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}";

        CompletionResponse response = provider.complete(new CompletionRequest("sys", "page text"));

        assertThat(server.requestPaths).containsExactly("/models/gemini-3.5-flash-lite:generateContent");
        assertThat(server.authHeaders.get(0)).isEqualTo("test-key");
        JsonNode body = JsonMapper.builder().build().readTree(server.requestBodies.get(0));
        assertThat(body.at("/systemInstruction/parts/0/text").asString()).isEqualTo("sys");
        assertThat(body.at("/contents/0/parts/0/text").asString()).isEqualTo("page text");
        assertThat(body.at("/generationConfig/responseMimeType").asString()).isEqualTo("application/json");
        assertThat(body.at("/generationConfig/thinkingConfig/thinkingLevel").asString()).isEqualTo("minimal");
        assertThat(body.at("/generationConfig/thinkingConfig/thinkingBudget").isMissingNode()).isTrue();
        assertThat(response.text()).isEqualTo("{}");
    }

    @Test
    void readsTheReportedTokenUsageWhenPresent() {
        server.responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}],"
            + "\"usageMetadata\":{\"promptTokenCount\":2000,\"totalTokenCount\":2150}}";
        assertThat(provider.complete(new CompletionRequest("hi")).totalTokens()).isEqualTo(2150);

        server.responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}";
        assertThat(provider.complete(new CompletionRequest("hi")).totalTokens()).isNull();
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

    /** The real body of 2026-10-25 (an embedding call on the free tier key): parked until released. */
    @Test
    void freeTier429ParksUntilReleasedAndLogsTheWholeQuotaId() throws Exception {
        server.status = 429;
        server.responseBody = realFreeTierBody();

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOfSatisfying(ProviderException.class, e -> {
                assertThat(e.exhausted()).isEqualTo(ProviderException.Quota.FREE_TIER);
                assertThat(e.getMessage()).contains("quotaId=EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier");
            });

        assertThat(provider.currentQuota().remaining()).isZero();
        assertThat(redis.getExpire("quota:gemini:parked")).isEqualTo(-1L);
    }

    /** The same body from a paid project (no FreeTier suffix): the usual minute park, quotaId still logged. */
    @Test
    void paidTier429ParksForTheLimitPeriod() throws Exception {
        server.status = 429;
        server.responseBody = realFreeTierBody().replace("-FreeTier", "");

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOfSatisfying(ProviderException.class, e -> {
                assertThat(e.exhausted()).isEqualTo(ProviderException.Quota.MINUTE);
                assertThat(e.getMessage()).contains("quotaId=EmbedContentRequestsPerMinutePerUserPerProjectPerModel ");
            });
        assertThat(redis.getExpire("quota:gemini:parked")).isBetween(0L, 60L);
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

    private static String realFreeTierBody() throws Exception {
        try (var in = GeminiProviderTest.class.getResourceAsStream("/ai/gemini-429-free-tier.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
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
