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

class GroqProviderTest {

    private FakeHttpServer server;
    private ProviderQuotaTracker tracker;
    private GroqProvider provider;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        server = new FakeHttpServer();
        tracker = new ProviderQuotaTracker(RedisTestSupport.template(), Clock.systemUTC(),
            Map.of("groq", new ProviderLimits(14400, 30)));
        provider = new GroqProvider(server.baseUrl(), "llama-3.3-70b-versatile", "test-key", tracker);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void mapsChatCompletionResponseAndSendsBearerAuth() {
        server.responseBody = "{\"choices\":[{\"message\":{\"content\":\"classificado: forum\"}}]}";

        CompletionResponse response = provider.complete(new CompletionRequest("sys", "classifique"));

        assertThat(server.requestPaths).containsExactly("/chat/completions");
        assertThat(server.authHeaders.get(0)).isEqualTo("Bearer test-key");
        assertThat(server.requestBodies.get(0)).contains("llama-3.3-70b-versatile").contains("classifique");
        assertThat(response.text()).isEqualTo("classificado: forum");
    }

    @Test
    void http429MarksQuotaExhausted() {
        server.status = 429;
        server.responseBody = "{\"error\":\"rate limit\"}";

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOf(ProviderException.class)
            .satisfies(e -> assertThat(((ProviderException) e).rateLimited()).isTrue());

        assertThat(provider.currentQuota().remaining()).isZero();
        assertThat(provider.currentQuota().availableWithMargin(5)).isFalse();
    }

    @Test
    void blankApiKeyReportsZeroQuotaAndRefuses() {
        GroqProvider noKey = new GroqProvider(server.baseUrl(), "m", "  ", tracker);

        assertThat(noKey.currentQuota().remaining()).isZero();
        assertThatThrownBy(() -> noKey.complete(new CompletionRequest("hi")))
            .isInstanceOf(ProviderException.class);
    }
}
