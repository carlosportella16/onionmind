package com.onionmind.agents;

import com.onionmind.intelligence.ChangeQuery;
import com.onionmind.intelligence.ChangesResponse.Change;
import com.onionmind.search.PassageRetriever.Retrieval;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** {@code changes}: summarized changes in the agent's window and categories, newest first. */
@Component
class ChangesTool implements AgentTool {

    static final int MAX_CHANGES = 20;

    private final ChangeQuery changes;

    ChangesTool(ChangeQuery changes) {
        this.changes = changes;
    }

    @Override
    public String name() {
        return "changes";
    }

    @Override
    public List<AgentRun.Source> collect(Agent agent, Retrieval retrieval) {
        Instant since = Instant.now().minus(Duration.ofDays(agent.windowDays()));
        List<AgentRun.Source> sources = new ArrayList<>();
        for (Change c : changes.find(since, agent.categories(), true, MAX_CHANGES).changes()) {
            StringBuilder text = new StringBuilder("Change on ").append(c.url()).append(" (version ")
                .append(c.fromVersion()).append(" to ").append(c.toVersion()).append(", detected ")
                .append(c.detectedAt()).append("): ").append(c.summary().text());
            if (!Objects.equals(c.categoryFrom(), c.categoryTo())) {
                text.append("\nCategory: ").append(c.categoryFrom()).append(" to ").append(c.categoryTo());
            }
            appendEntities(text, "Entities added: ", c.entitiesAdded());
            appendEntities(text, "Entities removed: ", c.entitiesRemoved());
            sources.add(new AgentRun.Source("C" + (sources.size() + 1), name(), c.url(), text.toString()));
        }
        return sources;
    }

    private static void appendEntities(StringBuilder text, String label, List<Map<String, String>> entities) {
        if (!entities.isEmpty()) {
            text.append('\n').append(label).append(entities.stream()
                .map(e -> e.get("display") + " (" + e.get("type") + ")").collect(Collectors.joining(", ")));
        }
    }
}
