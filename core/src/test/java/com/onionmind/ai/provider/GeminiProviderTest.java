package com.onionmind.ai.provider;

import com.onionmind.ai.FakeHttpServer;
import com.onionmind.ai.RedisTestSupport;
import com.onionmind.ai.routing.ProviderLimits;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiProviderTest {

    private FakeHttpServer server;
    private ProviderQuotaTracker tracker;
    private GeminiProvider provider;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        server = new FakeHttpServer();
        tracker = new ProviderQuotaTracker(RedisTestSupport.template(), Clock.systemUTC(),
            Map.of("gemini", new ProviderLimits(1500, 15)));
        provider = new GeminiProvider(server.baseUrl(), "gemini-2.5-flash", "test-key", tracker);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void mapsGenerateContentResponseAndSendsApiKeyHeader() {
        server.responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"traducao\"}]}}]}";

        CompletionResponse response = provider.complete(new CompletionRequest("sys", "traduza"));

        assertThat(server.requestPaths).containsExactly("/models/gemini-2.5-flash:generateContent");
        assertThat(server.authHeaders.get(0)).isEqualTo("test-key");
        assertThat(server.requestBodies.get(0)).contains("traduza").contains("sys");
        assertThat(response.text()).isEqualTo("traducao");
    }

    @Test
    void http429MarksQuotaExhausted() {
        server.status = 429;
        server.responseBody = "{\"error\":{\"code\":429}}";

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOf(ProviderException.class)
            .satisfies(e -> assertThat(((ProviderException) e).rateLimited()).isTrue());

        assertThat(provider.currentQuota().remaining()).isZero();
    }
}
