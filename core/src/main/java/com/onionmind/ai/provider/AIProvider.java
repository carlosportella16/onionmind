package com.onionmind.ai.provider;

/**
 * One adapter per cloud LLM provider (Gemini, OpenAI-compatible ones like DeepSeek). Consumers
 * never touch this — only the AIOrchestrator's routing layer does (SDD onionmind-sdd sec. 7.2).
 */
public interface AIProvider {

    /** Stable id used by the router's ladder ({@code ai.ladder}) and the quota tracker: "gemini" | "deepseek". */
    String id();

    /** Runs one completion. Throws {@link ProviderException} on HTTP/transport failure. */
    CompletionResponse complete(CompletionRequest request);

    /** Remaining headroom. {@link ProviderQuota#UNLIMITED} for a provider without configured limits. */
    ProviderQuota currentQuota();
}
