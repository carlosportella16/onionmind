package com.onionmind.ai.routing;

/**
 * Request and token ceilings for one cloud provider, from {@code ai.providers.<id>}. The
 * per-minute caps trip first under real concurrency. A provider with no entry is never gated
 * by quota; a token ceiling of 0 (not configured) leaves tokens untracked (ai-token-quota D5).
 */
public record ProviderLimits(long requestsPerDay, long requestsPerMinute, long tokensPerDay, long tokensPerMinute) {
}
