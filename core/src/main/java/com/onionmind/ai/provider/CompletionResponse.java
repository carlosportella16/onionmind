package com.onionmind.ai.provider;

/** Raw text the provider returned. Parsing/validation of that text is the decorator chain's job. */
public record CompletionResponse(String text) {
}
