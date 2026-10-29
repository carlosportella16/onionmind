package com.onionmind.ai.routing;

import com.onionmind.ai.AllProvidersExhaustedException;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.provider.AIProvider;
import com.onionmind.ai.provider.CompletionRequest;
import com.onionmind.ai.provider.CompletionResponse;
import com.onionmind.ai.provider.ProviderQuota;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
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
            new FakeProvider("gemini", geminiQuota)), LADDER, 5, 30);
    }

    private TaskContext ctx(int tokens) {
        return TaskContext.batch(TaskContext.TaskType.SUMMARIZE, tokens, "pt", false);
    }

    @Test
    void firstProviderWithQuotaServesWhateverTheTextSize() {
        CostOptimizerRouter router = router(HAS_QUOTA, ProviderQuota.UNLIMITED);

        assertThat(router.select(ctx(10), Set.of(), 0).id()).isEqualTo("gemini");
        assertThat(router.select(ctx(50_000), Set.of(), 0).id()).isEqualTo("gemini");
    }

    @Test
    void firstProviderWithoutQuotaFallsToTheUngatedLastRung() {
        assertThat(router(NO_QUOTA, ProviderQuota.UNLIMITED).select(ctx(100), Set.of(), 0).id()).isEqualTo("deepseek");
    }

    @Test
    void escalationSkipsWhatThisTaskAlreadyTried() {
        CostOptimizerRouter router = router(HAS_QUOTA, ProviderQuota.UNLIMITED);

        assertThat(router.select(ctx(100), Set.of("gemini"), 0).id()).isEqualTo("deepseek");
        assertThatThrownBy(() -> router.select(ctx(100), Set.of("gemini", "deepseek"), 0))
            .isInstanceOf(AllProvidersExhaustedException.class);
    }

    @Test
    void noUsableProviderIsAQuotaOnlyExhaustion() {
        assertThatThrownBy(() -> router(NO_QUOTA, NO_QUOTA).select(ctx(100), Set.of(), 0))
            .isInstanceOfSatisfying(AllProvidersExhaustedException.class, e -> assertThat(e.quotaOnly()).isTrue());
    }

    @Test
    void requestOverTheTokenBudgetGoesToTheNextRung() {
        // 1500 tokens left of 8000 this minute: a 2000-token request does not fit, a 500-token one does
        CostOptimizerRouter router = router(new ProviderQuota(1000, 1000, 1_500, 8_000), ProviderQuota.UNLIMITED);

        assertThat(router.select(interactive(), Set.of(), 2_000).id()).isEqualTo("deepseek");
        assertThat(router.select(interactive(), Set.of(), 500).id()).isEqualTo("gemini");
    }

    @Test
    void noProviderFitsTheRequestIsAQuotaOnlyExhaustion() {
        ProviderQuota tight = new ProviderQuota(1000, 1000, 1_500, 8_000);

        assertThatThrownBy(() -> router(tight, tight).select(interactive(), Set.of(), 2_000))
            .isInstanceOfSatisfying(AllProvidersExhaustedException.class, e -> assertThat(e.quotaOnly()).isTrue());
    }

    @Test
    void batchLeavesTheInteractiveReserveAlone() {
        // 30 of 100 requests left: inside batch's 5% + 30% margin, outside interactive's 5%
        CostOptimizerRouter router = router(new ProviderQuota(30, 100), ProviderQuota.UNLIMITED);

        assertThat(router.select(ctx(100), Set.of(), 0).id()).isEqualTo("deepseek");
        assertThat(router.select(interactive(), Set.of(), 0).id()).isEqualTo("gemini");
    }

    @Test
    void batchUsesTheProviderAboveTheReserve() {
        assertThat(router(new ProviderQuota(36, 100), ProviderQuota.UNLIMITED).select(ctx(100), Set.of(), 0).id())
            .isEqualTo("gemini");
    }

    private TaskContext interactive() {
        return new TaskContext(TaskContext.TaskType.SUMMARIZE, 100, "en", false, true, null, 0);
    }

    /** .onion text only goes to approved providers: every shipped profile's ladder is DeepSeek alone (D7). */
    @ParameterizedTest
    @ValueSource(strings = {"", "prod", "sandbox"})
    void defaultLadderOfEveryProfileHasOnlyDeepSeek(String profile) throws IOException {
        var env = new StandardEnvironment();
        var loader = new YamlPropertySourceLoader();
        loader.load("app", new ClassPathResource("application.yml")).forEach(env.getPropertySources()::addLast);
        if (!profile.isEmpty()) {
            loader.load(profile, new ClassPathResource("application-" + profile + ".yml"))
                .forEach(env.getPropertySources()::addFirst);
        }
        List<String> ladder = Binder.get(env).bind("ai.ladder", Bindable.listOf(String.class)).get();

        CostOptimizerRouter router = new CostOptimizerRouter(List.of(
            new FakeProvider("gemini", HAS_QUOTA), new FakeProvider("deepseek", ProviderQuota.UNLIMITED)), ladder, 5, 30);

        assertThat(router.ladderFor(ctx(100))).extracting(AIProvider::id).containsExactly("deepseek");
    }

    /** Used to NPE: the old router fell back to a hardcoded local-provider bean that no longer exists. */
    @Test
    void ladderIdWithoutABeanOrAnEmptyLadderThrowsInsteadOfNpe() {
        CostOptimizerRouter missingBean = new CostOptimizerRouter(
            List.of(new FakeProvider("gemini", NO_QUOTA)), LADDER, 5, 30);
        CostOptimizerRouter emptyLadder = new CostOptimizerRouter(
            List.of(new FakeProvider("gemini", HAS_QUOTA)), List.of(), 5, 30);

        assertThat(missingBean.ladderFor(ctx(100))).extracting(AIProvider::id).containsExactly("gemini");
        assertThatThrownBy(() -> missingBean.select(ctx(100), Set.of(), 0))
            .isInstanceOf(AllProvidersExhaustedException.class);
        assertThatThrownBy(() -> emptyLadder.select(ctx(100), Set.of(), 0))
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
