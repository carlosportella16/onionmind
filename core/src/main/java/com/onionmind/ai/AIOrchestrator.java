package com.onionmind.ai;

/**
 * The only AI surface the rest of the system sees. Processors never call a provider,
 * Spring AI, Ollama or Groq directly (SDD sec. 7.2).
 *
 * <p>{@code summarize}/{@code classify}/{@code translate} run through the decorator chain
 * and provider-escalation loop, and so does {@code extractEntities} (Fase 4 — named entities
 * only; wallets/onion addresses are pattern-matched by the caller, no LLM needed). {@code embed}
 * is single-provider (Ollama) — deterministic, no escalation.
 */
public interface AIOrchestrator {

    Summary summarize(String text, TaskContext ctx);

    Classification classify(String text, TaskContext ctx);

    Translation translate(String text, String targetLang, TaskContext ctx);

    LanguageDetection detectLanguage(String text, TaskContext ctx);

    EntityExtraction extractEntities(String text, TaskContext ctx);

    Embedding embed(String text, TaskContext ctx);
}
