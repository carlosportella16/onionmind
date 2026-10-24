package com.onionmind.ai.routing;

/**
 * Request ceilings for one cloud provider, from {@code ai.providers.<id>}. The per-minute cap
 * trips first under real concurrency. A provider with no entry is never gated by quota.
 */
public record ProviderLimits(long requestsPerDay, long requestsPerMinute) {
}
