package com.onionmind.ai.provider;

import com.onionmind.ai.FakeHttpServer;
import com.onionmind.ai.RedisTestSupport;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleProviderTest {

    private static final Map<String, Object> DEEPSEEK_EXTRA = Map.of(
        "response_format", Map.of("type", "json_object"),
        "thinking", Map.of("type", "disabled"));

    private final StringRedisTemplate redis = RedisTestSupport.template();
    private FakeHttpServer server;
    private ProviderQuotaTracker tracker;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        server = new FakeHttpServer();
        tracker = new ProviderQuotaTracker(redis, Clock.systemUTC(), Map.of());
        provider = deepseek("test-key");
    }

    private OpenAiCompatibleProvider deepseek(String key) {
        return new OpenAiCompatibleProvider("deepseek",
            new OpenAiCompatibleProvider.Settings(server.baseUrl(), "deepseek-flash", key, DEEPSEEK_EXTRA), tracker);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void sendsModelMessagesJsonModeAndThinkingOffWithBearerAuth() {
        server.responseBody = "{\"choices\":[{\"message\":{\"content\":\"{\\\"category\\\":\\\"forum\\\"}\"}}]}";

        CompletionResponse response = provider.complete(new CompletionRequest("sys", "classify"));

        assertThat(provider.id()).isEqualTo("deepseek");
        assertThat(server.requestPaths).containsExactly("/chat/completions");
        assertThat(server.authHeaders.get(0)).isEqualTo("Bearer test-key");
        assertThat(server.requestBodies.get(0))
            .contains("\"model\":\"deepseek-flash\"", "\"role\":\"system\"", "\"content\":\"classify\"")
            .contains("\"response_format\":{\"type\":\"json_object\"}", "\"thinking\":{\"type\":\"disabled\"}");
        assertThat(response.text()).isEqualTo("{\"category\":\"forum\"}");
    }

    /** DeepSeek's JSON mode "may occasionally return empty content" — the ValidationDecorator rejects it. */
    @Test
    void readsTheReportedTokenUsageWhenPresent() {
        server.responseBody = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],\"usage\":{\"total_tokens\":980}}";
        assertThat(provider.complete(new CompletionRequest("hi")).totalTokens()).isEqualTo(980);

        server.responseBody = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}";
        assertThat(provider.complete(new CompletionRequest("hi")).totalTokens()).isNull();
    }

    @Test
    void emptyContentComesBackAsEmptyText() {
        server.responseBody = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";

        assertThat(provider.complete(new CompletionRequest("hi")).text()).isEmpty();
    }

    @Test
    void http429ParksUntilTheMinuteRolls() {
        server.status = 429;
        server.responseBody = "{\"error\":{\"message\":\"Rate Limit Reached\"}}";

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOfSatisfying(ProviderException.class, e -> {
                assertThat(e.rateLimited()).isTrue();
                assertThat(e.retryable()).isFalse();
            });

        assertThat(provider.currentQuota().availableWithMargin(5)).isFalse();
        assertThat(redis.getExpire("quota:deepseek:parked")).isBetween(0L, 60L);
    }

    @Test
    void http402ParksUntilReleasedEvenWithoutConfiguredLimits() {
        server.status = 402;
        server.responseBody = "{\"error\":{\"message\":\"Insufficient Balance\"}}";

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOfSatisfying(ProviderException.class,
                e -> assertThat(e.exhausted()).isEqualTo(ProviderException.Quota.BALANCE));

        assertThat(provider.currentQuota().availableWithMargin(5)).isFalse();
        assertThat(redis.getExpire("quota:deepseek:parked")).isEqualTo(-1L);
    }

    @Test
    void blankApiKeyReportsZeroQuotaAndRefuses() {
        OpenAiCompatibleProvider noKey = deepseek("  ");

        assertThat(noKey.currentQuota().remaining()).isZero();
        assertThatThrownBy(() -> noKey.complete(new CompletionRequest("hi")))
            .isInstanceOf(ProviderException.class);
        assertThat(server.requestPaths).isEmpty();
    }

    /** The shipped application.yml entry binds to exactly the extra body DeepSeek needs. */
    @Test
    void applicationYmlDeclaresDeepSeekWithJsonModeAndThinkingOff() throws Exception {
        var env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
            .forEach(env.getPropertySources()::addLast);

        Map<String, OpenAiCompatibleProvider.Settings> declared = Binder.get(env)
            .bind("ai.openai-compatible", Bindable.mapOf(String.class, OpenAiCompatibleProvider.Settings.class))
            .get();

        assertThat(declared).containsOnlyKeys("deepseek");
        assertThat(declared.get("deepseek").baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(declared.get("deepseek").model()).isEqualTo("deepseek-flash");
        assertThat(declared.get("deepseek").extraBody()).isEqualTo(DEEPSEEK_EXTRA);
    }
}
