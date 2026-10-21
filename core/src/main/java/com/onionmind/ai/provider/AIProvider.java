package com.onionmind.ai.provider;

/**
 * One adapter per LLM provider (Ollama, Groq, Gemini). Consumers never touch this —
 * only the AIOrchestrator's routing layer does (SDD onionmind-sdd sec. 7.2).
 */
public interface AIProvider {

    /** Stable id used by the router's ladder and the quota tracker: "ollama" | "groq" | "gemini". */
    String id();

    /** Runs one completion. Throws {@link ProviderException} on HTTP/transport failure. */
    CompletionResponse complete(CompletionRequest request);

    /** Remaining free-tier headroom. {@link ProviderQuota#UNLIMITED} for the local provider. */
    ProviderQuota currentQuota();
}
