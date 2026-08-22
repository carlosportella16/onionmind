package com.onionmind.search;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {
    private final JdbcTemplate jdbc;

    public SearchController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (q.isBlank()) return ResponseEntity.badRequest().build();
        size = Math.min(size, 100); // never more than 100 per page

        var results = jdbc.query("""
            SELECT id, url, source_type,
                   ts_headline('simple', extracted_text, websearch_to_tsquery('simple', ?),
                                'MaxFragments=2, MaxWords=40, MinWords=20') AS snippet,
                   ts_rank(search_vector, websearch_to_tsquery('simple', ?)) AS rank,
                   version, first_seen_at, last_seen_at
            FROM pages
            WHERE search_vector @@ websearch_to_tsquery('simple', ?)
            ORDER BY rank DESC
            LIMIT ? OFFSET ?
            """, (rs, rowNum) -> new SearchResult(
                rs.getLong("id"), rs.getString("url"), rs.getString("source_type"),
                rs.getString("snippet"), rs.getDouble("rank"), rs.getInt("version"),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant()
            ), q, q, q, size, page * size);

        long total = jdbc.queryForObject("""
            SELECT count(*) FROM pages
            WHERE search_vector @@ websearch_to_tsquery('simple', ?)
            """, Long.class, q);

        return ResponseEntity.ok(new SearchResponse(results, total, page, size));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
            "total_pages", jdbc.queryForObject("SELECT count(*) FROM pages", Long.class),
            "total_versions", jdbc.queryForObject("SELECT count(*) FROM page_versions", Long.class),
            "sources", jdbc.queryForList("SELECT source_type, count(*) as n FROM pages GROUP BY source_type")
        );
    }
}
