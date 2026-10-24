package com.onionmind.ai.provider;

/**
 * Request and token headroom for a provider. {@code Long.MAX_VALUE} as a limit means that side
 * is not tracked; with neither tracked (the last rung, DeepSeek) the provider is never gated by
 * quota — only parked.
 */
public record ProviderQuota(long remaining, long limit, long tokensRemaining, long tokenLimit) {

    public static final ProviderQuota UNLIMITED = new ProviderQuota(Long.MAX_VALUE, Long.MAX_VALUE);

    /** Request headroom only; tokens untracked. */
    public ProviderQuota(long remaining, long limit) {
        this(remaining, limit, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public boolean unlimited() {
        return limit == Long.MAX_VALUE && tokenLimit == Long.MAX_VALUE;
    }

    /** True when there is more headroom than {@code marginPercent}% of the binding limit. */
    public boolean availableWithMargin(int marginPercent) {
        return allows(marginPercent, 0);
    }

    /**
     * Request headroom above the margin AND room for {@code estimatedTokens} with the token
     * margin still left over (ai-token-quota D3) — a request that only fits by eating the
     * margin is not sent.
     */
    public boolean allows(int marginPercent, long estimatedTokens) {
        if (unlimited()) {
            return true;
        }
        boolean requestsOk = limit == Long.MAX_VALUE || remaining > (limit * marginPercent) / 100;
        boolean tokensOk = tokenLimit == Long.MAX_VALUE
            || tokensRemaining - estimatedTokens > (tokenLimit * marginPercent) / 100;
        return requestsOk && tokensOk;
    }
}
