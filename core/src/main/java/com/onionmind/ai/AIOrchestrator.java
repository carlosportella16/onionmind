package com.onionmind.ai;

import java.util.List;

/**
 * The only AI surface the rest of the system sees. Processors never call a provider,
 * Spring AI, Ollama or Groq directly (SDD sec. 7.2).
 *
 * <p>{@code summarize}/{@code classify}/{@code translate}/{@code extractEntities} run through
 * the decorator chain and provider-escalation loop. {@code embed} is single-provider (Ollama) —
 * deterministic, no escalation.
 */
public interface AIOrchestrator {

    Summary summarize(String text, TaskContext ctx);

    Classification classify(String text, TaskContext ctx);

    Translation translate(String text, String targetLang, TaskContext ctx);

    LanguageDetection detectLanguage(String text, TaskContext ctx);

    /** Phase 4 — see {@code graph.EntityExtractionListener}. An empty list is a confident "no entities found", not a failure. */
    List<Entity> extractEntities(String text, TaskContext ctx);

    /** Phase 4 — see {@code intelligence.DiffSummaryListener}. A natural-language summary of what changed between two page versions — {@code summarize} can't do this, its prompt is fixed to "summarize this one page". */
    Summary summarizeDiff(String previousText, String currentText, TaskContext ctx);

    Embedding embed(String text, TaskContext ctx);
}
