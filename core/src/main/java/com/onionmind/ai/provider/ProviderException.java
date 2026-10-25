package com.onionmind.ai.provider;

/**
 * HTTP/transport failure talking to a provider. {@code retryable} → same-provider retry
 * makes sense (timeout, 5xx). {@code rateLimited} → the provider is out of quota (429) or
 * balance (402); the router should move on, not retry, and {@link #exhausted()} says for
 * how long (replace-ollama-with-gemini-deepseek D5).
 */
public class ProviderException extends RuntimeException {

    /**
     * Which ceiling the provider hit: it decides how long the provider stays parked. BALANCE (402)
     * and FREE_TIER (a 429 from a free-tier quota) park until released.
     */
    public enum Quota { MINUTE, DAY, BALANCE, FREE_TIER }

    private final boolean retryable;
    private final Quota exhausted;

    public ProviderException(String message, boolean retryable, boolean rateLimited) {
        super(message);
        this.retryable = retryable;
        this.exhausted = rateLimited ? Quota.MINUTE : null;
    }

    /** Out of quota or balance — never worth a same-provider retry. */
    public ProviderException(String message, Quota exhausted) {
        super(message);
        this.retryable = false;
        this.exhausted = exhausted;
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
        this.retryable = true;      // unknown transport error — worth a same-provider retry
        this.exhausted = null;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean rateLimited() {
        return exhausted != null;
    }

    /** The ceiling hit, or null when this is not a quota/balance failure. */
    public Quota exhausted() {
        return exhausted;
    }
}
