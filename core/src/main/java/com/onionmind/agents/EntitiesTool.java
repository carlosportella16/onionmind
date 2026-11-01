package com.onionmind.agents;

import com.onionmind.graph.EntityView;
import com.onionmind.graph.GraphRepository;
import com.onionmind.search.PassageRetriever.Retrieval;
import com.onionmind.search.SearchResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code entities}: the entities the topic's pages mention most, with the pages citing them and
 * what they co-occur with. Only with {@code graph.enabled=true}; otherwise unavailable and the
 * run goes on without it.
 */
@Component
class EntitiesTool implements AgentTool {

    static final int MAX_ENTITIES = 5;
    static final int MAX_LISTED = 5;

    private final ObjectProvider<GraphRepository> graph;

    EntitiesTool(ObjectProvider<GraphRepository> graph) {
        this.graph = graph;
    }

    @Override
    public String name() {
        return "entities";
    }

    @Override
    public boolean available() {
        return graph.getIfAvailable() != null;
    }

    @Override
    public List<AgentRun.Source> collect(Agent agent, Retrieval retrieval) {
        List<String> urls = retrieval.results().stream().map(SearchResult::url).toList();
        if (urls.isEmpty()) {
            return List.of();
        }
        GraphRepository repo = graph.getObject();
        List<AgentRun.Source> sources = new ArrayList<>();
        for (String id : repo.mostMentionedIn(urls, MAX_ENTITIES)) {
            repo.find(id).ifPresent(e -> sources.add(new AgentRun.Source("E" + (sources.size() + 1), name(),
                "/api/entities/" + e.type() + "/" + URLEncoder.encode(e.key(), StandardCharsets.UTF_8).replace("+", "%20"),
                describe(e))));
        }
        return sources;
    }

    private static String describe(EntityView e) {
        StringBuilder text = new StringBuilder("Entity: ").append(e.display()).append(" (").append(e.type())
            .append(")\nMentioned by: ").append(e.pages().stream().limit(MAX_LISTED)
                .map(EntityView.PageMention::url).collect(Collectors.joining(", ")));
        if (!e.related().isEmpty()) {
            text.append("\nAppears with: ").append(e.related().stream().limit(MAX_LISTED)
                .map(r -> r.display() + " (" + r.type() + ", " + r.sharedPages() + " shared pages)")
                .collect(Collectors.joining(", ")));
        }
        return text.toString();
    }
}
