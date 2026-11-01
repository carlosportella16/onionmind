package com.onionmind.agents;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.Report;
import com.onionmind.ai.TaskContext;
import com.onionmind.search.PassageRetriever;
import com.onionmind.search.PassageRetriever.Retrieval;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs an agent (design D2–D5): collect from the enabled tools, cut to the token ceiling,
 * generate the cited report, persist. Synchronous with a deadline per cost profile; the daily
 * job uses the same path, always as batch. Nothing found → the no-material report without an
 * LLM call; not even one source fits the ceiling → {@code failed/budget} without one either.
 */
@Service
class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** Same k as the chat (chat.top-k). */
    static final int TOP_K = 8;
    // ponytail: chars/4 estimate (ai-token-quota D2) and a flat allowance for the system prompt and
    // the report itself; measure real usage per run if the estimate proves off.
    static final int RESERVED_TOKENS = 1000;

    record Outcome(String status, String failureReason, String report, List<AgentRun.Source> sources,
                   List<String> unavailableTools, int tokensUsed) {

        static Outcome failed(String reason, List<String> unavailableTools) {
            return new Outcome("failed", reason, null, List.of(), unavailableTools, 0);
        }
    }

    private final AgentRepository repo;
    private final PassageRetriever retriever;
    private final AIOrchestrator ai;
    private final Map<String, AgentTool> tools;
    private final Duration interactiveTimeout;
    private final Duration batchTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    AgentRunner(AgentRepository repo, PassageRetriever retriever, AIOrchestrator ai, List<AgentTool> tools,
                @Value("${agents.interactive-timeout:20s}") Duration interactiveTimeout,
                @Value("${agents.batch-timeout:60s}") Duration batchTimeout) {
        this.repo = repo;
        this.retriever = retriever;
        this.ai = ai;
        this.tools = tools.stream().collect(Collectors.toMap(AgentTool::name, Function.identity()));
        this.interactiveTimeout = interactiveTimeout;
        this.batchTimeout = batchTimeout;
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }

    /** {@code POST /api/agents/{id}/runs}: with the agent's own cost profile. */
    AgentRun run(Agent agent) {
        return execute(agent, repo.startRun(agent.id(), "manual"), agent.interactive());
    }

    /** The daily job's run, as batch; empty when the agent already had one today. */
    Optional<AgentRun> runDaily(Agent agent) {
        return repo.startDailyRun(agent.id()).map(runId -> execute(agent, runId, false));
    }

    private AgentRun execute(Agent agent, long runId, boolean interactive) {
        Duration deadline = interactive ? interactiveTimeout : batchTimeout;
        Outcome outcome;
        try {
            outcome = CompletableFuture.supplyAsync(() -> produce(agent, interactive), executor)
                .orTimeout(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .join();
        } catch (CompletionException e) {
            boolean late = e.getCause() instanceof TimeoutException;
            log.warn("Agent {} run {} failed: {}", agent.id(), runId, late ? "deadline " + deadline : e.getCause().toString());
            outcome = Outcome.failed(late ? "timeout" : "error", List.of());
        }
        repo.finishRun(runId, outcome);
        return repo.run(agent.id(), runId).orElseThrow();
    }

    private Outcome produce(Agent agent, boolean interactive) {
        List<AgentTool> enabled = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (String name : Agent.TOOLS) {
            if (agent.tools().contains(name)) {
                AgentTool tool = tools.get(name);
                if (tool.available()) {
                    enabled.add(tool);
                } else {
                    unavailable.add(name);
                }
            }
        }
        boolean needsPages = enabled.stream().anyMatch(t -> !t.name().equals("changes"));
        Retrieval retrieval = needsPages
            ? retriever.retrieve(agent.topic(), null, TOP_K, Integer.MAX_VALUE)
            : new Retrieval(List.of(), List.of());
        List<AgentRun.Source> collected = enabled.stream().flatMap(t -> t.collect(agent, retrieval).stream()).toList();
        if (collected.isEmpty()) {
            return new Outcome("succeeded", null, Report.NO_MATERIAL, List.of(), unavailable, 0);
        }

        // Cut in priority order (search by RRF, changes newest first, entities): whatever doesn't fit is left out.
        int fixed = tokens(agent.instructions()) + tokens(agent.topic());
        int budget = agent.maxTokensPerRun() - RESERVED_TOKENS - fixed;
        SequencedMap<String, AgentRun.Source> kept = new LinkedHashMap<>();
        for (AgentRun.Source s : collected) {
            int t = tokens(s.text());
            if (t <= budget) {
                kept.put(s.id(), s);
                budget -= t;
            }
        }
        if (kept.isEmpty()) {
            return Outcome.failed("budget", unavailable);
        }

        SequencedMap<String, String> texts = new LinkedHashMap<>();
        kept.forEach((id, s) -> texts.put(id, s.text()));
        int promptTokens = fixed + kept.values().stream().mapToInt(s -> tokens(s.text())).sum();
        Report report = ai.report(agent.instructions(), agent.topic(), texts,
            new TaskContext(TaskContext.TaskType.REPORT, promptTokens, null, false, interactive, null, 0));
        return new Outcome("succeeded", null, report.text(), report.citations().stream().map(kept::get).toList(),
            unavailable, promptTokens + tokens(report.text()));
    }

    private static int tokens(String text) {
        return (text.length() + 3) / 4;
    }
}
