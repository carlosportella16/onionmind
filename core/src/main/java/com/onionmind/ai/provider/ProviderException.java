package com.onionmind.ai.provider;

/**
 * HTTP/transport failure talking to a provider. {@code retryable} → same-provider retry
 * makes sense (timeout, 5xx). {@code rateLimited} → the provider is out of quota (429);
 * the router should move on, not retry.
 */
public class ProviderException extends RuntimeException {

    private final boolean retryable;
    private final boolean rateLimited;

    public ProviderException(String message, boolean retryable, boolean rateLimited) {
        super(message);
        this.retryable = retryable;
        this.rateLimited = rateLimited;
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
        this.retryable = true;      // unknown transport error — worth a same-provider retry
        this.rateLimited = false;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean rateLimited() {
        return rateLimited;
    }
}
