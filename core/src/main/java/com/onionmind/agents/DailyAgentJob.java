package com.onionmind.agents;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The "automatic reports": once a day, every agent with {@code daily = true} runs as batch.
 * A second firing the same (UTC) day creates nothing — the partial unique index on
 * {@code agent_runs} decides, not this class. Off without generation (ai.enabled), like the
 * other AI jobs.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
class DailyAgentJob {

    private final AgentRepository repo;
    private final AgentRunner runner;

    DailyAgentJob(AgentRepository repo, AgentRunner runner) {
        this.repo = repo;
        this.runner = runner;
    }

    @Scheduled(cron = "${agents.daily.cron:0 0 6 * * *}", zone = "UTC")
    void run() {
        repo.findDaily().forEach(runner::runDaily);
    }
}
