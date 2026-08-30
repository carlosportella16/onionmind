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
import com.onionmind.ai.routing.CostOptimizerRouter;
import com.onionmind.ai.routing.ProviderQuotaTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * The real Fase 3 orchestrator (SDD sec. 7.2). {@code summarize}/{@code classify}/{@code translate}
 * run: cache → escalation loop (router → retry → provider → validate). A low-confidence
 * result after the last escalation is returned, not thrown — only a total transport
 * failure throws {@link AllProvidersExhaustedException}. {@code embed} bypasses all of
 * this — one provider, deterministic.
 */
@Component
@ConditionalOnExpression("${ai.enabled:false}")
public class DefaultAIOrchestrator implements AIOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultAIOrchestrator.class);

    private final CostOptimizerRouter router;
    private final ProviderQuotaTracker quotaTracker;
    private final RetryDecorator retry;
    private final ValidationDecorator validator;
    private final CacheDecorator cache;
    private final MetricsDecorator metrics;
    private final OllamaEmbedder embedder;
    private final double minConfidence;
    private final int maxEscalations;

    public DefaultAIOrchestrator(CostOptimizerRouter router,
                                 ProviderQuotaTracker quotaTracker,
                                 RetryDecorator retry,
                                 ValidationDecorator validator,
                                 CacheDecorator cache,
                                 MetricsDecorator metrics,
                                 OllamaEmbedder embedder,
                                 @Value("${ai.min-confidence:0.7}") double minConfidence,
                                 @Value("${ai.max-escalations:2}") int maxEscalations) {
        this.router = router;
        this.quotaTracker = quotaTracker;
        this.retry = retry;
        this.validator = validator;
        this.cache = cache;
        this.metrics = metrics;
        this.embedder = embedder;
        this.minConfidence = minConfidence;
        this.maxEscalations = maxEscalations;
    }

    @Override
    public Summary summarize(String text, TaskContext ctx) {
        CompletionRequest request = new CompletionRequest(
            "Você resume páginas da web em português. Responda SOMENTE com JSON: "
                + "{\"summary\": \"<2 a 3 frases>\", \"confidence\": <0 a 1>}.",
            text);
        ValidatedResult r = execute(text, request, ctx);
        return new Summary(r.primary(), r.confidence());
    }

    @Override
    public Classification classify(String text, TaskContext ctx) {
        CompletionRequest request = new CompletionRequest(
            "Classifique a página em uma única categoria curta. Responda SOMENTE com JSON: "
                + "{\"category\": \"<categoria>\", \"confidence\": <0 a 1>}.",
            text);
        ValidatedResult r = execute(text, request, ctx);
        return new Classification(r.primary(), r.confidence());
    }

    @Override
    public Translation translate(String text, String targetLang, TaskContext ctx) {
        CompletionRequest request = new CompletionRequest(
            "Traduza o texto para " + targetLang + ". Responda SOMENTE com JSON: "
                + "{\"translation\": \"<texto traduzido>\", \"detectedLanguage\": \"<iso-639-1>\", "
                + "\"confidence\": <0 a 1>}.",
            text);
        ValidatedResult r = execute(text, request, ctx);
        return new Translation(r.primary(), r.secondary(), r.confidence());
    }

    @Override
    public Embedding embed(String text, TaskContext ctx) {
        return embedder.embed(text);
    }

    private ValidatedResult execute(String cacheText, CompletionRequest request, TaskContext ctx) {
        var cached = cache.get(ctx.type(), cacheText);
        if (cached.isPresent()) {
            metrics.recordOutcome("cache", ctx.type(), "cache_hit");
            return cached.get();
        }

        ValidatedResult best = null;
        ProviderException lastError = null;
        TaskContext current = ctx;

        for (int attempt = 0; attempt <= maxEscalations; attempt++) {
            AIProvider provider = router.select(current.withAttempt(attempt));
            ValidatedResult validated;
            try {
                CompletionResponse raw = metrics.timed(ctx.type(),
                    () -> retry.execute(() -> provider.complete(request)));
                quotaTracker.recordUsage(provider.id());
                validated = validator.validate(raw, ctx.type());
            } catch (ProviderException e) {
                lastError = e;
                metrics.recordOutcome(provider.id(), ctx.type(), "transient_fail");
                log.warn("AI provider {} failed for {}: {}", provider.id(), ctx.type(), e.getMessage());
                continue;
            }

            metrics.recordConfidence(ctx.type(), validated.confidence());
            if (best == null || validated.confidence() > best.confidence()) {
                best = validated;
            }
            if (validated.usable() && validated.confidence() >= minConfidence) {
                metrics.recordOutcome(provider.id(), ctx.type(), "ok");
                cache.put(ctx.type(), cacheText, validated);
                return validated;
            }
            current = current.withPreviousConfidence(validated.confidence());
        }

        if (best != null && best.usable()) {
            metrics.recordOutcome("none", ctx.type(), "low_confidence");
            cache.put(ctx.type(), cacheText, best);
            return best;
        }

        metrics.recordOutcome("none", ctx.type(), "exhausted");
        throw new AllProvidersExhaustedException("all providers failed for " + ctx.type(), lastError);
    }
}
