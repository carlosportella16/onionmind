package com.onionmind.ai;

/**
 * Every provider in the ladder failed hard (transport, not just low confidence). The
 * escalation loop only throws this when it has no usable result at all — a low-confidence
 * result is returned, not thrown (SDD sec. 7.2).
 *
 * <p>{@link #quotaOnly()} is true when the only reason was quota: every provider was out of
 * quota, parked or without a key, or answered 429/402. That is a state to wait out, not a
 * failure — the batch jobs leave the item for the next run without spending an attempt
 * (replace-ollama-with-gemini-deepseek D4).
 */
public class AllProvidersExhaustedException extends RuntimeException {

    private final boolean quotaOnly;

    public AllProvidersExhaustedException(String message, Throwable cause) {
        this(message, false, cause);
    }

    public AllProvidersExhaustedException(String message, boolean quotaOnly, Throwable cause) {
        super(message, cause);
        this.quotaOnly = quotaOnly;
    }

    public boolean quotaOnly() {
        return quotaOnly;
    }

    /** True when {@code e}, or what it wraps, is a quota-only exhaustion. */
    public static boolean isQuotaOnly(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AllProvidersExhaustedException x) {
                return x.quotaOnly();
            }
        }
        return false;
    }
}
