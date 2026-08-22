package com.onionmind.search;

import java.util.List;

public record SearchResponse(
    List<SearchResult> results, long total, int page, int size
) {}
