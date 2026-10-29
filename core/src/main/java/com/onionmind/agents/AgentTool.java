package com.onionmind.agents;

import com.onionmind.search.PassageRetriever.Retrieval;

import java.util.List;

/**
 * One of the fixed query tools an agent may enable (design D1/D2): collects sources, never
 * calls an LLM. {@code retrieval} is the topic's search, run once per agent run and shared by
 * the tools that need the pages found for the topic.
 */
interface AgentTool {

    /** One of {@link Agent#TOOLS}. */
    String name();

    /** False when what the tool reads is switched off (the graph). */
    default boolean available() {
        return true;
    }

    /** Sources in rank order, ids numbered from 1 with the tool's letter ({@code S}, {@code C}, {@code E}). */
    List<AgentRun.Source> collect(Agent agent, Retrieval retrieval);
}
