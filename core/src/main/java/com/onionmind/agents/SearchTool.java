package com.onionmind.agents;

import com.onionmind.search.PassageRetriever.Passage;
import com.onionmind.search.PassageRetriever.Retrieval;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** {@code search}: the topic's passages from the PassageRetriever, in RRF order, as the chat sends them. */
@Component
class SearchTool implements AgentTool {

    @Override
    public String name() {
        return "search";
    }

    @Override
    public List<AgentRun.Source> collect(Agent agent, Retrieval retrieval) {
        List<AgentRun.Source> sources = new ArrayList<>();
        for (Passage p : retrieval.passages()) {
            String summary = p.page().summary() == null ? "" : "Summary: " + p.page().summary() + "\n";
            sources.add(new AgentRun.Source("S" + (sources.size() + 1), name(), p.page().url(),
                "URL: " + p.page().url() + "\n" + summary + "\n" + p.text()));
        }
        return sources;
    }
}
