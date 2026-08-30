package com.onionmind.ai.provider;

/**
 * Free-tier headroom for a provider. {@code limit == Long.MAX_VALUE} marks the local
 * provider, which is never gated by quota (SDD sec. 7.3 — "Ollama nunca estoura quota").
 */
public record ProviderQuota(long remaining, long limit) {

    public static final ProviderQuota UNLIMITED = new ProviderQuota(Long.MAX_VALUE, Long.MAX_VALUE);

    public boolean unlimited() {
        return limit == Long.MAX_VALUE;
    }

    /** True when there is more headroom than {@code marginPercent}% of the daily limit. */
    public boolean availableWithMargin(int marginPercent) {
        return unlimited() || remaining > (limit * marginPercent) / 100;
    }
}
