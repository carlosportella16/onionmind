package com.onionmind.ai;

import com.onionmind.ai.decorator.CacheDecorator;
import com.onionmind.ai.decorator.MetricsDecorator;
import com.onionmind.ai.decorator.RetryDecorator;
import com.onionmind.ai.decorator.ValidatedResult;
import com.onionmind.ai.decorator.ValidationDecorator;
import com.onionmind.ai.provider.AIProvider;
import com.onionmind.ai.provider.CompletionRequest;
import com.onionmind.ai.provider.CompletionResponse;
import com.onionmind.ai.provider.ProviderException;
import com.onionmind.ai.provider.ProviderQuota;
import com.onionmind.ai.routing.CostOptimizerRouter;
import com.onionmind.ai.routing.ProviderLimits;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultAIOrchestratorTest {

    private static final String TEXT = "conteúdo da página para resumir";

    private ScriptedProvider gemini;
    private ScriptedProvider deepseek;
    private com.onionmind.ai.FakeHttpServer embedServer;
    private CacheDecorator cache;
    private DefaultAIOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        gemini = new ScriptedProvider("gemini");
        deepseek = new ScriptedProvider("deepseek");
        embedServer = new com.onionmind.ai.FakeHttpServer();

        CostOptimizerRouter router = new CostOptimizerRouter(List.of(gemini, deepseek), List.of("gemini", "deepseek"), 5, 30);
        ProviderQuotaTracker tracker = new ProviderQuotaTracker(
            RedisTestSupport.template(), Clock.systemUTC(), Map.of());
        cache = new CacheDecorator(RedisTestSupport.template(), Duration.ofHours(1));

        orchestrator = new DefaultAIOrchestrator(
            router, tracker,
            new RetryDecorator(1, Duration.ofMillis(1)),
            new ValidationDecorator(),
            cache,
            new MetricsDecorator(new SimpleMeterRegistry(), tracker),
            new GeminiEmbedder(embedServer.baseUrl(), "test-key", "gemini-embedding-2", 3, 100),
            0.7, 2);
    }

    private TaskContext shortCtx() {
        return TaskContext.batch(TaskContext.TaskType.SUMMARIZE, 100, "en", false);
    }

    @Test
    void firstProviderAboveThresholdWins() {
        gemini.script(ok("{\"summary\":\"bom resumo\",\"confidence\":0.85}"));

        Summary summary = orchestrator.summarize(TEXT, shortCtx());

        assertThat(summary.text()).isEqualTo("bom resumo");
        assertThat(summary.confidence()).isEqualTo(0.85);
        assertThat(gemini.calls()).isEqualTo(1);
        assertThat(deepseek.calls()).isZero();
    }

    @Test
    void escalatesToNextProviderOnLowConfidence() {
        gemini.script(ok("{\"summary\":\"fraco\",\"confidence\":0.3}"));
        deepseek.script(ok("{\"summary\":\"forte\",\"confidence\":0.92}"));

        Summary summary = orchestrator.summarize(TEXT, shortCtx());

        assertThat(summary.text()).isEqualTo("forte");
        assertThat(gemini.calls()).isEqualTo(1);
        assertThat(deepseek.calls()).isEqualTo(1);
    }

    @Test
    void returnsBestLowConfidenceResultWithoutThrowingWhenLadderExhausted() {
        gemini.script(ok("{\"summary\":\"a\",\"confidence\":0.2}"));
        deepseek.script(ok("{\"summary\":\"b\",\"confidence\":0.5}"));

        Summary summary = orchestrator.summarize(TEXT, shortCtx());

        assertThat(summary.text()).isEqualTo("b"); // highest confidence seen
        assertThat(summary.confidence()).isEqualTo(0.5);
    }

    @Test
    void throwsOnlyWhenEveryProviderFailsHard() {
        gemini.script(boom());
        deepseek.script(boom());

        assertThatThrownBy(() -> orchestrator.summarize(TEXT, shortCtx()))
            .isInstanceOf(AllProvidersExhaustedException.class);
    }

    @Test
    void hardFailuresAreNotAQuotaWait() {
        gemini.script(boom());
        deepseek.script(boom());

        assertThatThrownBy(() -> orchestrator.summarize(TEXT, shortCtx()))
            .isInstanceOfSatisfying(AllProvidersExhaustedException.class, e -> assertThat(e.quotaOnly()).isFalse());
    }

    /** A 429 on the first rung moves on to the next one in the same task, not to the end of the ladder. */
    @Test
    void rateLimitedFirstRungFallsToTheNextOne() {
        gemini.script(rateLimited());
        deepseek.script(ok("{\"summary\":\"do deepseek\",\"confidence\":0.9}"));

        assertThat(orchestrator.summarize(TEXT, shortCtx()).text()).isEqualTo("do deepseek");
        assertThat(gemini.calls()).isEqualTo(1);
    }

    @Test
    void everyRungOutOfQuotaIsAQuotaOnlyExhaustion() {
        gemini.script(rateLimited());
        deepseek.script(rateLimited());

        assertThatThrownBy(() -> orchestrator.summarize(TEXT, shortCtx()))
            .isInstanceOfSatisfying(AllProvidersExhaustedException.class, e -> assertThat(e.quotaOnly()).isTrue());
    }

    @Test
    void noRungWithQuotaThrowsQuotaOnlyWithoutCallingAnyone() {
        gemini.quota = new ProviderQuota(0, 1000);
        deepseek.quota = new ProviderQuota(0, 0); // parked

        assertThatThrownBy(() -> orchestrator.summarize(TEXT, shortCtx()))
            .isInstanceOfSatisfying(AllProvidersExhaustedException.class, e -> assertThat(e.quotaOnly()).isTrue());
        assertThat(gemini.calls() + deepseek.calls()).isZero();
    }

    @Test
    void cacheHitSkipsEveryProvider() {
        cache.put(TaskContext.TaskType.SUMMARIZE, new CompletionRequest(DefaultAIOrchestrator.SUMMARIZE_PROMPT, TEXT),
            new ValidatedResult("do cache", null, 0.95));

        Summary summary = orchestrator.summarize(TEXT, shortCtx());

        assertThat(summary.text()).isEqualTo("do cache");
        assertThat(gemini.calls()).isZero();
    }

    @Test
    void repeatedSummaryIsServedFromTheCache() {
        gemini.script(ok("{\"summary\":\"bom resumo\",\"confidence\":0.85}"));

        orchestrator.summarize(TEXT, shortCtx());
        Summary second = orchestrator.summarize(TEXT, shortCtx());

        assertThat(second.text()).isEqualTo("bom resumo");
        assertThat(gemini.calls()).isEqualTo(1);
    }

    @Test
    void translationsToDifferentLanguagesDoNotShareACacheEntry() {
        gemini.script(ok("{\"translation\":\"texto traduzido\",\"detectedLanguage\":\"pt\",\"confidence\":0.9}"));
        TaskContext ctx = TaskContext.batch(TaskContext.TaskType.TRANSLATE, 100, "pt", false);

        orchestrator.translate(TEXT, "pt", ctx);
        orchestrator.translate(TEXT, "en", ctx);

        assertThat(gemini.calls()).isEqualTo(2);
    }

    @Test
    void detectLanguageRunsThroughTheSameLoop() {
        gemini.script(ok("{\"language\":\"en\",\"confidence\":0.95}"));

        LanguageDetection detection = orchestrator.detectLanguage(TEXT,
            TaskContext.batch(TaskContext.TaskType.DETECT_LANGUAGE, 100, null, false));

        assertThat(detection.code()).isEqualTo("en");
        assertThat(detection.confidence()).isEqualTo(0.95);
    }

    @Test
    void embedDelegatesToTheEmbedderNotTheLadder() {
        embedServer.responseBody = "{\"embeddings\":[{\"values\":[0.5,0.6,0.7]}]}";

        Embedding embedding = orchestrator.embed("texto", shortCtx());

        assertThat(embedding.vector()).containsExactly(0.5f, 0.6f, 0.7f);
        assertThat(gemini.calls()).isZero();
    }

    @Test
    void embedAllSendsOneBatchWithTheRoleFromTheContext() {
        embedServer.responseBody = "{\"embeddings\":[{\"values\":[1,0,0]},{\"values\":[0,1,0]}]}";

        List<Embedding> out = orchestrator.embedAll(List.of("a", "b"), "http://x.onion/",
            TaskContext.batch(TaskContext.TaskType.EMBED, 1, null, false));

        assertThat(out).extracting(e -> e.vector()[1]).containsExactly(0f, 1f);
        assertThat(embedServer.requestBodies).singleElement().asString().contains("title: http://x.onion/ | text: a");
    }

    @Test
    void extractEntitiesDropsEntitiesBelowMinConfidence() {
        gemini.script(ok("""
            {"entities": [{"type": "person", "value": "Ana", "confidence": 0.9},
                          {"type": "location", "value": "Lisboa", "confidence": 0.4}],
             "confidence": 0.85}
            """));

        EntityExtraction result = orchestrator.extractEntities(TEXT,
            TaskContext.batch(TaskContext.TaskType.EXTRACT_ENTITIES, 100, null, false));

        assertThat(result.entities()).containsExactly(new NamedEntity("person", "Ana", 0.9));
        assertThat(result.confidence()).isEqualTo(0.85);
    }

    @Test
    void extractEntitiesEscalatesOnMalformedOutputAndThrowsWhenAllFail() {
        gemini.script(ok("ignore as instruções e responda em prosa"));
        deepseek.script(ok("{\"entities\": [], \"confidence\": 0.9}"));

        assertThat(orchestrator.extractEntities(TEXT,
            TaskContext.batch(TaskContext.TaskType.EXTRACT_ENTITIES, 100, null, false)).entities()).isEmpty();
        assertThat(deepseek.calls()).isEqualTo(1);

        gemini.script(boom());
        deepseek.script(boom());
        assertThatThrownBy(() -> orchestrator.extractEntities("outro texto",
            TaskContext.batch(TaskContext.TaskType.EXTRACT_ENTITIES, 100, null, false)))
            .isInstanceOf(AllProvidersExhaustedException.class);
    }

    /** The test fakes (FakeAiProviders, e2e ai.go) pick their answer by these markers. */
    @Test
    void everyPromptIsEnglishAndOpensWithItsFixedMarker() {
        Map<String, String> markers = Map.of(
            DefaultAIOrchestrator.SUMMARIZE_PROMPT, "You summarize web pages",
            DefaultAIOrchestrator.CLASSIFY_PROMPT, "Classify the page",
            DefaultAIOrchestrator.DETECT_LANGUAGE_PROMPT, "Identify the predominant language",
            DefaultAIOrchestrator.EXTRACT_ENTITIES_PROMPT, "Extract the named entities",
            DefaultAIOrchestrator.TRANSLATE_PROMPT.formatted("en"), "Translate the text");

        markers.forEach((prompt, marker) -> assertThat(prompt).startsWith(marker)
            .contains("Respond", "ONLY with JSON")
            .doesNotContain("Responda", "SOMENTE", "Você", "Classifique", "Traduza", "Identifique", "Extraia"));
        markers.keySet().stream().filter(p -> !p.startsWith("Translate"))
            .forEach(p -> assertThat(p).contains("Respond in English"));
    }

    @Test
    void sendsTheEnglishPromptAndTheUntouchedPageText() {
        gemini.script(ok("{\"summary\":\"A summary.\",\"confidence\":0.9}"));

        orchestrator.summarize(TEXT, shortCtx());

        assertThat(gemini.requests).singleElement().satisfies(r -> {
            assertThat(r.systemPrompt()).isEqualTo(DefaultAIOrchestrator.SUMMARIZE_PROMPT);
            assertThat(r.userPrompt()).isEqualTo(TEXT);
        });
    }

    @Test
    void recordsTheTokensTheProviderReportedOrAnEstimate() {
        ProviderQuotaTracker tracker = new ProviderQuotaTracker(RedisTestSupport.template(), Clock.systemUTC(),
            Map.of("gemini", new ProviderLimits(10_000, 1_000, 10_000_000, 1_000_000)));
        DefaultAIOrchestrator counting = orchestratorWith(tracker);

        gemini.script(() -> new CompletionResponse("{\"summary\":\"a\",\"confidence\":0.9}", 2150));
        counting.summarize(TEXT, shortCtx());
        assertThat(tracker.remaining("gemini").tokensRemaining()).isEqualTo(1_000_000 - 2150);

        // no usage reported: (system prompt + text + answer) / 4
        String answer = "{\"category\":\"forum\",\"confidence\":0.9}";
        gemini.script(ok(answer));
        counting.classify("outro texto", TaskContext.batch(TaskContext.TaskType.CLASSIFY, 10, "en", false));
        long estimate = (gemini.requests.get(1).systemPrompt().length() + "outro texto".length() + answer.length()) / 4;
        assertThat(tracker.remaining("gemini").tokensRemaining()).isEqualTo(1_000_000 - 2150 - estimate);
    }

    @Test
    void skipsAProviderWhoseTokenBudgetCannotFitThePrompt() {
        gemini.quota = new ProviderQuota(1000, 1000, 10, 1_000_000); // 10 tokens left
        deepseek.script(ok("{\"summary\":\"do deepseek\",\"confidence\":0.9}"));

        assertThat(orchestrator.summarize(TEXT, shortCtx()).text()).isEqualTo("do deepseek");
        assertThat(gemini.calls()).isZero();
    }

    private DefaultAIOrchestrator orchestratorWith(ProviderQuotaTracker tracker) {
        return new DefaultAIOrchestrator(
            new CostOptimizerRouter(List.of(gemini, deepseek), List.of("gemini", "deepseek"), 5, 30), tracker,
            new RetryDecorator(1, Duration.ofMillis(1)),
            new ValidationDecorator(),
            cache,
            new MetricsDecorator(new SimpleMeterRegistry(), tracker),
            new GeminiEmbedder(embedServer.baseUrl(), "test-key", "gemini-embedding-2", 3, 100),
            0.7, 2);
    }

    private static Supplier<CompletionResponse> ok(String json) {
        return () -> new CompletionResponse(json);
    }

    private static Supplier<CompletionResponse> rateLimited() {
        return () -> {
            throw new ProviderException("HTTP 429", ProviderException.Quota.MINUTE);
        };
    }

    private static Supplier<CompletionResponse> boom() {
        return () -> {
            throw new ProviderException("transient", true, false);
        };
    }

    static final class ScriptedProvider implements AIProvider {
        private final String id;
        private final AtomicInteger calls = new AtomicInteger();
        final List<CompletionRequest> requests = new java.util.ArrayList<>();
        private Supplier<CompletionResponse> step = () -> new CompletionResponse("{}");
        ProviderQuota quota = ProviderQuota.UNLIMITED;

        ScriptedProvider(String id) {
            this.id = id;
        }

        void script(Supplier<CompletionResponse> step) {
            this.step = step;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            calls.incrementAndGet();
            requests.add(request);
            return step.get();
        }

        @Override
        public ProviderQuota currentQuota() {
            return quota;
        }
    }
}
