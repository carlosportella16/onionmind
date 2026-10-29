/**
 * Agent Playground (Fase 5, phase5-agent-playground): an agent is configuration — instructions,
 * a topic, filters, a subset of fixed query tools and a cost profile — over what already runs.
 * A run collects context deterministically from the enabled tools (search passages, recent
 * changes, graph entities) and the LLM only writes the cited report. Single user: no identity.
 * Uses the other modules through their APIs only (design D2); the graph is optional.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"search", "intelligence", "graph", "ai", "content"})
package com.onionmind.agents;
