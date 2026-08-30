package com.onionmind.ai.provider;

import com.onionmind.ai.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaProviderTest {

    private FakeHttpServer server;
    private OllamaProvider provider;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        provider = new OllamaProvider(server.baseUrl(), "llama3.1:8b", Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void mapsGenerateResponseToText() {
        server.responseBody = "{\"response\":\"um resumo curto\"}";

        CompletionResponse response = provider.complete(new CompletionRequest("system", "resuma isto"));

        assertThat(server.requestPaths).containsExactly("/api/generate");
        assertThat(server.requestBodies.get(0)).contains("llama3.1:8b").contains("resuma isto").contains("system");
        assertThat(response.text()).isEqualTo("um resumo curto");
    }

    @Test
    void serverErrorIsRetryableProviderException() {
        server.status = 500;
        server.responseBody = "{\"error\":\"model missing\"}";

        assertThatThrownBy(() -> provider.complete(new CompletionRequest("hi")))
            .isInstanceOf(ProviderException.class)
            .satisfies(e -> {
                assertThat(((ProviderException) e).retryable()).isTrue();
                assertThat(((ProviderException) e).rateLimited()).isFalse();
            });
    }

    @Test
    void quotaIsUnlimited() {
        assertThat(provider.currentQuota()).isEqualTo(ProviderQuota.UNLIMITED);
        assertThat(provider.currentQuota().availableWithMargin(50)).isTrue();
    }
}
