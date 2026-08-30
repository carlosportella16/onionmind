package com.onionmind.ai;

/**
 * The only AI surface the rest of the system sees. Processors never call a provider,
 * Spring AI, Ollama or Groq directly (SDD sec. 7.2).
 *
 * <p>{@code summarize}/{@code classify}/{@code translate} run through the decorator chain
 * and provider-escalation loop. {@code embed} is single-provider (Ollama) — deterministic,
 * no escalation. {@code extractEntities} is reserved for Fase 4 and is not on this interface yet.
 */
public interface AIOrchestrator {

    Summary summarize(String text, TaskContext ctx);

    Classification classify(String text, TaskContext ctx);

    Translation translate(String text, String targetLang, TaskContext ctx);

    Embedding embed(String text, TaskContext ctx);
}
