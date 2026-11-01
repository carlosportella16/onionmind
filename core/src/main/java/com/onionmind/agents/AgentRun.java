package com.onionmind.agents;

import java.time.Instant;
import java.util.List;

/**
 * One execution of an agent (agents/agent-runs). {@code sources} are the ones the report
 * cites; {@code unavailableTools} were enabled but could not be used (entities without the
 * graph). {@code tokensUsed} is the chars/4 estimate of what was sent and received.
 */
record AgentRun(long id, long agentId, String trigger, String status, String failureReason, String report,
                List<Source> sources, List<String> unavailableTools, int tokensUsed,
                Instant startedAt, Instant finishedAt, Long durationMillis) {

    /** {@code id} is what the report cites ({@code S1}, {@code C1}, {@code E1}…); {@code url} is its link. */
    record Source(String id, String tool, String url, String text) {
    }
}
