package com.onionmind.ai.routing;

import com.onionmind.ai.TaskContext;
import com.onionmind.ai.provider.AIProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Simple rules, not ML (SDD sec. 7.3): short non-critical text starts local; long or
 * critical text starts on the cloud; the local provider is always the floor and is
 * never gated by quota.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class CostOptimizerRouter {

    private static final int SHORT_TEXT_TOKENS = 500;
    private static final List<String> LOCAL_FIRST = List.of("ollama", "groq", "gemini");
    private static final List<String> CLOUD_FIRST = List.of("groq", "gemini", "ollama");

    private final Map<String, AIProvider> byId;
    private final int marginPercent;

    public CostOptimizerRouter(List<AIProvider> providers,
                               @Value("${ai.safety-margin-percent}") int marginPercent) {
        this.byId = providers.stream().collect(Collectors.toMap(AIProvider::id, Function.identity()));
        this.marginPercent = marginPercent;
    }

    /** The provider order for this task, before quota filtering. */
    public List<AIProvider> ladderFor(TaskContext ctx) {
        List<String> order = (ctx.approxTokens() < SHORT_TEXT_TOKENS && !ctx.critical())
            ? LOCAL_FIRST : CLOUD_FIRST;
        return order.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * The provider for this attempt: the {@code attemptNumber}-th provider in the ladder
     * that still has quota, falling back to the local provider (never gated).
     */
    public AIProvider select(TaskContext ctx) {
        List<AIProvider> viable = ladderFor(ctx).stream()
            .filter(p -> p.currentQuota().availableWithMargin(marginPercent))
            .toList();
        int idx = Math.max(0, ctx.attemptNumber());
        if (idx < viable.size()) {
            return viable.get(idx);
        }
        return byId.get("ollama");
    }
}
