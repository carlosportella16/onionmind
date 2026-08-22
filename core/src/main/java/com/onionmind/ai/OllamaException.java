package com.onionmind.ai;

/** Wraps failures talking to Ollama (network, non-2xx responses, serialization). */
public class OllamaException extends RuntimeException {
    public OllamaException(String message) {
        super(message);
    }

    public OllamaException(String message, Throwable cause) {
        super(message, cause);
    }
}
