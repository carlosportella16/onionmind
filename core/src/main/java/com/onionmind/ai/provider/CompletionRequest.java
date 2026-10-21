package com.onionmind.ai.provider;

/**
 * Provider-agnostic completion input. The model name is provider config, not part of
 * the request — a task doesn't know or care which model answers it.
 */
public record CompletionRequest(String systemPrompt, String userPrompt) {

    public CompletionRequest(String userPrompt) {
        this(null, userPrompt);
    }
}
