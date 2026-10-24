package com.onionmind.ai.routing;

import com.onionmind.ai.AllProvidersExhaustedException;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.provider.AIProvider;
import com.onionmind.ai.provider.CompletionRequest;
import com.onionmind.ai.provider.CompletionResponse;
import com.onionmind.ai.provider.ProviderQuota;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostOptimizerRouterTest {

    private static final ProviderQuota HAS_QUOTA = new ProviderQuota(1000, 1000);
    private static final ProviderQuota NO_QUOTA = new ProviderQuota(0, 1000);
    private static final List<String> LADDER = List.of("gemini", "deepseek");

    private CostOptimizerRouter router(ProviderQuota geminiQuota, ProviderQuota deepseekQuota) {
        return new CostOptimizerRouter(List.of(
            new FakeProvider("deepseek", deepseekQuota),
            new FakeProvider("gemini", geminiQuota)), LADDER, 5);
    }

    private TaskContext ctx(int tokens) {
        return TaskContext.batch(TaskContext.TaskType.SUMMARIZE, tokens, "pt", false);
    }

    @Test
    void firstProviderWithQuotaServesWhateverTheTextSize() {
        CostOptimizerRouter router = router(HAS_QUOTA, ProviderQuota.UNLIMITED);

        assertThat(router.select(ctx(10), Set.of()).id()).isEqualTo("gemini");
        assertThat(router.select(ctx(50_000), Set.of()).id()).isEqualTo("gemini");
    }

    @Test
    void firstProviderWithoutQuotaFallsToTheUngatedLastRung() {
        assertThat(router(NO_QUOTA, ProviderQuota.UNLIMITED).select(ctx(100), Set.of()).id()).isEqualTo("deepseek");
    }

    @Test
    void escalationSkipsWhatThisTaskAlreadyTried() {
        CostOptimizerRouter router = router(HAS_QUOTA, ProviderQuota.UNLIMITED);

        assertThat(router.select(ctx(100), Set.of("gemini")).id()).isEqualTo("deepseek");
        assertThatThrownBy(() -> router.select(ctx(100), Set.of("gemini", "deepseek")))
            .isInstanceOf(AllProvidersExhaustedException.class);
    }

    @Test
    void noUsableProviderIsAQuotaOnlyExhaustion() {
        assertThatThrownBy(() -> router(NO_QUOTA, NO_QUOTA).select(ctx(100), Set.of()))
            .isInstanceOfSatisfying(AllProvidersExhaustedException.class, e -> assertThat(e.quotaOnly()).isTrue());
    }

    /** Used to NPE: the old router fell back to byId.get("ollama"), which no longer exists. */
    @Test
    void ladderIdWithoutABeanOrAnEmptyLadderThrowsInsteadOfNpe() {
        CostOptimizerRouter missingBean = new CostOptimizerRouter(
            List.of(new FakeProvider("gemini", NO_QUOTA)), LADDER, 5);
        CostOptimizerRouter emptyLadder = new CostOptimizerRouter(
            List.of(new FakeProvider("gemini", HAS_QUOTA)), List.of(), 5);

        assertThat(missingBean.ladderFor(ctx(100))).extracting(AIProvider::id).containsExactly("gemini");
        assertThatThrownBy(() -> missingBean.select(ctx(100), Set.of()))
            .isInstanceOf(AllProvidersExhaustedException.class);
        assertThatThrownBy(() -> emptyLadder.select(ctx(100), Set.of()))
            .isInstanceOf(AllProvidersExhaustedException.class);
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
