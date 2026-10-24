package com.onionmind.ai;

import java.util.List;

/**
 * The only AI surface the rest of the system sees. Processors, jobs and search never call
 * a provider directly (SDD sec. 7.2).
 *
 * <p>{@code summarize}/{@code classify}/{@code translate} run through the decorator chain
 * and provider-escalation loop, and so does {@code extractEntities} (Fase 4 — named entities
 * only; wallets/onion addresses are pattern-matched by the caller, no LLM needed). {@code embed}
 * and {@code embedAll} are single-provider (Gemini) — deterministic, no escalation; the role
 * (query vs. document chunk) comes from {@link TaskContext#interactive()}.
 */
public interface AIOrchestrator {

    Summary summarize(String text, TaskContext ctx);

    Classification classify(String text, TaskContext ctx);

    Translation translate(String text, String targetLang, TaskContext ctx);

    LanguageDetection detectLanguage(String text, TaskContext ctx);

    EntityExtraction extractEntities(String text, TaskContext ctx);

    Embedding embed(String text, TaskContext ctx);

    /**
     * Embeds {@code texts} in order, batched into as few provider calls as possible.
     * {@code title} names the document the chunks come from; queries ignore it.
     */
    default List<Embedding> embedAll(List<String> texts, String title, TaskContext ctx) {
        return texts.stream().map(t -> embed(t, ctx)).toList();
    }
}
