package com.onionmind.ai.routing;

import com.onionmind.ai.TaskContext;
import com.onionmind.ai.provider.AIProvider;
import com.onionmind.ai.provider.CompletionRequest;
import com.onionmind.ai.provider.CompletionResponse;
import com.onionmind.ai.provider.ProviderQuota;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostOptimizerRouterTest {

    private static final ProviderQuota HAS_QUOTA = new ProviderQuota(1000, 1000);
    private static final ProviderQuota NO_QUOTA = new ProviderQuota(0, 1000);

    private final AIProvider ollama = new FakeProvider("ollama", ProviderQuota.UNLIMITED);

    private CostOptimizerRouter router(ProviderQuota groqQuota, ProviderQuota geminiQuota) {
        return new CostOptimizerRouter(List.of(
            ollama,
            new FakeProvider("groq", groqQuota),
            new FakeProvider("gemini", geminiQuota)), 5);
    }

    private TaskContext ctx(int tokens, boolean critical, int attempt) {
        return new TaskContext(TaskContext.TaskType.SUMMARIZE, tokens, "pt", critical, false, null, attempt);
    }

    @Test
    void shortNonCriticalStartsLocal() {
        assertThat(router(HAS_QUOTA, HAS_QUOTA).select(ctx(200, false, 0)).id()).isEqualTo("ollama");
    }

    @Test
    void longTextStartsOnCloud() {
        assertThat(router(HAS_QUOTA, HAS_QUOTA).select(ctx(2000, false, 0)).id()).isEqualTo("groq");
    }

    @Test
    void criticalShortTextStartsOnCloud() {
        assertThat(router(HAS_QUOTA, HAS_QUOTA).select(ctx(100, true, 0)).id()).isEqualTo("groq");
    }

    @Test
    void escalationWalksTheLadder() {
        CostOptimizerRouter router = router(HAS_QUOTA, HAS_QUOTA);
        assertThat(router.select(ctx(2000, false, 0)).id()).isEqualTo("groq");
        assertThat(router.select(ctx(2000, false, 1)).id()).isEqualTo("gemini");
        assertThat(router.select(ctx(2000, false, 2)).id()).isEqualTo("ollama");
        assertThat(router.select(ctx(2000, false, 3)).id()).isEqualTo("ollama"); // past the end → floor
    }

    @Test
    void skipsProvidersWithoutQuotaAndFallsBackToLocal() {
        CostOptimizerRouter router = router(NO_QUOTA, NO_QUOTA);
        assertThat(router.select(ctx(2000, false, 0)).id()).isEqualTo("ollama");
    }

    @Test
    void skipsOnlyTheDrainedCloudProvider() {
        CostOptimizerRouter router = router(NO_QUOTA, HAS_QUOTA);
        assertThat(router.select(ctx(2000, false, 0)).id()).isEqualTo("gemini");
    }

    private record FakeProvider(String id, ProviderQuota quota) implements AIProvider {
        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return new CompletionResponse("x");
        }

        @Override
        public ProviderQuota currentQuota() {
            return quota;
        }
    }
}
