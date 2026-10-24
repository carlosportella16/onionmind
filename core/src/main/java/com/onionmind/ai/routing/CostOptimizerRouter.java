package com.onionmind.ai.routing;

import com.onionmind.ai.AllProvidersExhaustedException;
import com.onionmind.ai.TaskContext;
import com.onionmind.ai.provider.AIProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One configurable ladder, {@code ai.ladder}, for every task (replace-ollama-with-gemini-deepseek
 * D3): cheapest first, the last rung usually a provider with no configured limits (DeepSeek),
 * which only a park takes out. There is no local floor: with nothing usable the router throws a
 * quota-only {@link AllProvidersExhaustedException}, and the batch jobs wait for quota (D4).
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class CostOptimizerRouter {

    private final Map<String, AIProvider> byId;
    private final List<String> ladder;
    private final int marginPercent;

    public CostOptimizerRouter(List<AIProvider> providers,
                               @Value("${ai.ladder}") List<String> ladder,
                               @Value("${ai.safety-margin-percent}") int marginPercent) {
        this.byId = providers.stream().collect(Collectors.toMap(AIProvider::id, Function.identity()));
        this.ladder = ladder;
        this.marginPercent = marginPercent;
    }

    /** The provider order for this task, before quota filtering: the configured ids that have a bean. */
    public List<AIProvider> ladderFor(TaskContext ctx) {
        return ladder.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /**
     * The first provider in the ladder that still has quota and this task hasn't tried yet —
     * a provider parked by a 429 on the previous attempt is skipped, not counted.
     */
    public AIProvider select(TaskContext ctx, Collection<String> tried) {
        return ladderFor(ctx).stream()
            .filter(p -> !tried.contains(p.id()))
            .filter(p -> p.currentQuota().availableWithMargin(marginPercent))
            .findFirst()
            .orElseThrow(() -> new AllProvidersExhaustedException(
                "no AI provider with quota left for " + ctx.type(), true, null));
    }
}
