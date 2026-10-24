package com.onionmind.ai.provider;

/**
 * Raw text the provider returned. Parsing/validation of that text is the decorator chain's job.
 * {@code totalTokens} is the usage the provider reported, or {@code null} when it reported none
 * — the orchestrator then estimates it (ai-token-quota D2).
 */
public record CompletionResponse(String text, Integer totalTokens) {

    public CompletionResponse(String text) {
        this(text, null);
    }
}
