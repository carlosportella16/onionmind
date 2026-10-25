package com.onionmind.chat;

import com.onionmind.search.SearchResult;

import java.util.List;

/**
 * {@code answer} is English and empty when {@code degraded}; {@code sources} are only the
 * passages the answer cites; {@code results} are the search results, always filled.
 */
record ChatResponse(String answer, List<Source> sources, List<SearchResult> results, boolean degraded) {

    /** A cited passage: its short id in the answer ({@code S1}…), the page and the passage text. */
    record Source(String id, String url, String snippet) {
    }
}
