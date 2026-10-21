package com.onionmind.ai.routing;

/** Free-tier ceilings for one cloud provider. The per-minute cap trips first under real concurrency. */
public record ProviderLimits(long daily, long perMinute) {
}
