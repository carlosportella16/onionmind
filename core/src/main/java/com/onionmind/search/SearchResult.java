package com.onionmind.search;

import java.time.Instant;

public record SearchResult(
    Long id, String url, String sourceType, String snippet,
    Double rank, Integer version, Instant firstSeenAt, Instant lastSeenAt
) {}
