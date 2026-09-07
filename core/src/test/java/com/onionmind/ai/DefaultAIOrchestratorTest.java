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

    private ScriptedProvider ollama;
    private ScriptedProvider groq;
    private ScriptedProvider gemini;
    private com.onionmind.ai.FakeHttpServer embedServer;
    private CacheDecorator cache;
    private DefaultAIOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        RedisTestSupport.flushAll();
        ollama = new ScriptedProvider("ollama");
        groq = new ScriptedProvider("groq");
        gemini = new ScriptedProvider("gemini");
        embedServer = new com.onionmind.ai.FakeHttpServer();

        CostOptimizerRouter router = new CostOptimizerRouter(List.of(ollama, groq, gemini), 5);
        ProviderQuotaTracker tracker = new ProviderQuotaTracker(
            RedisTestSupport.template(), Clock.systemUTC(), Map.of());
        cache = new CacheDecorator(RedisTestSupport.template(), Duration.ofHours(1));

        orchestrator = new DefaultAIOrchestrator(
            router, tracker,
            new RetryDecorator(1, Duration.ofMillis(1)),
            new ValidationDecorator(),
            cache,
            new MetricsDecorator(new SimpleMeterRegistry(), tracker),
            new OllamaEmbedder(embedServer.baseUrl(), "nomic-embed-text"),
            0.7, 2);
    }

    private TaskContext shortCtx() {
        return TaskContext.batch(TaskContext.TaskType.SUMMARIZE, 100, "en", false);
    }

    @Test
    void firstProviderAboveThresholdWins() {
        ollama.script(ok("{\"summary\":\"bom resumo\",\"confidence\":0.85}"));

        Summary summary = orchestrator.summarize(TEXT, shortCtx());

        assertThat(summary.text()).isEqualTo("bom resumo");
        assertThat(summary.confidence()).isEqualTo(0.85);
        assertThat(ollama.calls()).isEqualTo(1);
        assertThat(groq.calls()).isZero();
    }

    @Test
    void escalatesToNextProviderOnLowConfidence() {
        ollama.script(ok("{\"summary\":\"fraco\",\"confidence\":0.3}"));
        groq.script(ok("{\"summary\":\"forte\",\"confidence\":0.92}"));

        Summary summary = orchestrator.summarize(TEXT, shortCtx());

        assertThat(summary.text()).isEqualTo("forte");
        assertThat(ollama.calls()).isEqualTo(1);
        assertThat(groq.calls()).isEqualTo(1);
        assertThat(gemini.calls()).isZero();
    }

    @Test
    void returnsBestLowConfidenceResultWithoutThrowingWhenLadderExhausted() {
        ollama.script(ok("{\"summary\":\"a\",\"confidence\":0.2}"));
        groq.script(ok("{\"summary\":\"b\",\"confidence\":0.5}"));
        gemini.script(ok("{\"summary\":\"c\",\"confidence\":0.4}"));

        Summary summary = orchestrator.summarize(TEXT, shortCtx());

        assertThat(summary.text()).isEqualTo("b"); // highest confidence seen
        assertThat(summary.confidence()).isEqualTo(0.5);
    }

    @Test
    void throwsOnlyWhenEveryProviderFailsHard() {
        ollama.script(boom());
        groq.script(boom());
        gemini.script(boom());

        assertThatThrownBy(() -> orchestrator.summarize(TEXT, shortCtx()))
            .isInstanceOf(AllProvidersExhaustedException.class);
    }

    @Test
    void cacheHitSkipsEveryProvider() {
        cache.put(TaskContext.TaskType.SUMMARIZE, TEXT, new ValidatedResult("do cache", null, 0.95));

        Summary summary = orchestrator.summarize(TEXT, shortCtx());

        assertThat(summary.text()).isEqualTo("do cache");
        assertThat(ollama.calls()).isZero();
    }

    @Test
    void detectLanguageRunsThroughTheSameLoop() {
        ollama.script(ok("{\"language\":\"en\",\"confidence\":0.95}"));

        LanguageDetection detection = orchestrator.detectLanguage(TEXT,
            TaskContext.batch(TaskContext.TaskType.DETECT_LANGUAGE, 100, null, false));

        assertThat(detection.code()).isEqualTo("en");
        assertThat(detection.confidence()).isEqualTo(0.95);
    }

    @Test
    void extractEntitiesRunsThroughTheSameEscalationLoop() {
        ollama.script(ok("[{\"type\":\"CRYPTO_WALLET\",\"value\":\"1A2b3C\",\"confidence\":0.9}]"));

        List<Entity> entities = orchestrator.extractEntities(TEXT, shortCtx());

        assertThat(entities).containsExactly(new Entity(Entity.EntityType.CRYPTO_WALLET, "1A2b3C", 0.9));
        assertThat(ollama.calls()).isEqualTo(1);
        assertThat(groq.calls()).isZero();
    }

    @Test
    void extractEntitiesEscalatesOnLowConfidence() {
        ollama.script(ok("[{\"type\":\"PERSON\",\"value\":\"Ana\",\"confidence\":0.2}]"));
        groq.script(ok("[{\"type\":\"PERSON\",\"value\":\"Ana\",\"confidence\":0.95}]"));

        List<Entity> entities = orchestrator.extractEntities(TEXT, shortCtx());

        assertThat(entities).containsExactly(new Entity(Entity.EntityType.PERSON, "Ana", 0.95));
    }

    @Test
    void extractEntitiesTreatsAConfidentEmptyResultAsUsable() {
        ollama.script(ok("[]"));

        List<Entity> entities = orchestrator.extractEntities(TEXT, shortCtx());

        assertThat(entities).isEmpty();
        assertThat(groq.calls()).isZero(); // confidence 1.0 for "no entities found" — no escalation needed
    }

    @Test
    void extractEntitiesThrowsOnlyWhenEveryProviderFailsHard() {
        ollama.script(boom());
        groq.script(boom());
        gemini.script(boom());

        assertThatThrownBy(() -> orchestrator.extractEntities(TEXT, shortCtx()))
            .isInstanceOf(AllProvidersExhaustedException.class);
    }

    @Test
    void summarizeDiffSendsBothVersionsAndRunsThroughTheSameLoop() {
        ollama.script(ok("{\"summary\":\"a página passou a mencionar bitcoin\",\"confidence\":0.88}"));

        Summary diff = orchestrator.summarizeDiff("texto antigo", "texto novo com bitcoin", shortCtx());

        assertThat(diff.text()).isEqualTo("a página passou a mencionar bitcoin");
        assertThat(diff.confidence()).isEqualTo(0.88);
        assertThat(ollama.calls()).isEqualTo(1);
    }

    @Test
    void embedDelegatesToTheEmbedderNotTheLadder() {
        embedServer.responseBody = "{\"embeddings\":[[0.5,0.6,0.7]]}";

        Embedding embedding = orchestrator.embed("texto", shortCtx());

        assertThat(embedding.vector()).containsExactly(0.5f, 0.6f, 0.7f);
        assertThat(ollama.calls()).isZero();
    }

    private static Supplier<CompletionResponse> ok(String json) {
        return () -> new CompletionResponse(json);
    }

    private static Supplier<CompletionResponse> boom() {
        return () -> {
            throw new ProviderException("transient", true, false);
        };
    }

    static final class ScriptedProvider implements AIProvider {
        private final String id;
        private final AtomicInteger calls = new AtomicInteger();
        private Supplier<CompletionResponse> step = () -> new CompletionResponse("{}");

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
            return step.get();
        }

        @Override
        public ProviderQuota currentQuota() {
            return ProviderQuota.UNLIMITED;
        }
    }
}
