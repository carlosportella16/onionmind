package com.onionmind.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Postgres full-text half of the search (SDD Fase 1, sec. 6.8): {@code ts_rank} over the
 * {@code simple} dictionary. Shared by /api/search and the chat's PassageRetriever, which only
 * differ in the {@code ts_headline} window they ask for. A quarantined page has no
 * extracted_text, so it never matches.
 */
@Component
class FullTextSearch {

    /** The result-list snippet: two short fragments, matches in {@code <b>}. */
    static final String SNIPPET = "MaxFragments=2, MaxWords=40, MinWords=20";
    /** A ~120-word window the chat sends as context and shows as plain text (phase5-rag-chat D4). */
    static final String PASSAGE = "MaxWords=120, MinWords=80, StartSel=\"\", StopSel=\"\"";

    private final JdbcTemplate jdbc;

    FullTextSearch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<SearchResult> search(String q, int size, int offset, String headline) {
        return jdbc.query("""
            SELECT id, url, source_type,
                   ts_headline('simple', extracted_text, websearch_to_tsquery('simple', ?), ?) AS snippet,
                   ts_rank(search_vector, websearch_to_tsquery('simple', ?)) AS rank,
                   version, first_seen_at, last_seen_at,
                   summary->>'text' AS summary, category->>'category' AS category
            FROM pages
            WHERE search_vector @@ websearch_to_tsquery('simple', ?)
            ORDER BY rank DESC
            LIMIT ? OFFSET ?
            """, (rs, rowNum) -> new SearchResult(
                rs.getLong("id"), rs.getString("url"), rs.getString("source_type"),
                rs.getString("snippet"), rs.getDouble("rank"), rs.getInt("version"),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(),
                rs.getString("summary"), rs.getString("category")
            ), q, headline, q, q, size, offset);
    }

    long total(String q) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM pages
            WHERE search_vector @@ websearch_to_tsquery('simple', ?)
            """, Long.class, q);
    }
}
