package com.onionmind.intelligence;

import com.onionmind.content.Categories;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * "What changed in this category since yesterday" — served from {@code page_changes}, never
 * a live LLM call (sdd sec. 9). Trivial changes are not changes.
 */
@RestController
@RequestMapping("/api")
class ChangesController {

    static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
    static final Duration MAX_WINDOW = Duration.ofDays(30);
    static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    ChangesController(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    ChangesController(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/changes")
    ResponseEntity<ChangesResponse> changes(@RequestParam(required = false) String since,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(defaultValue = "20") int limit) {
        Instant now = clock.instant();
        Instant from;
        try {
            from = since == null ? now.minus(DEFAULT_WINDOW) : Instant.parse(since);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }
        if (from.isAfter(now) || from.isBefore(now.minus(MAX_WINDOW)) || limit < 1
            || (category != null && !Categories.TAXONOMY.contains(category))) {
            return ResponseEntity.badRequest().build();
        }
        int size = Math.min(limit, MAX_LIMIT);

        String filter = """
            FROM page_changes
            WHERE status <> 'trivial' AND detected_at >= ? AND (?::text IS NULL OR category_to = ?)
            """;
        Timestamp ts = Timestamp.from(from);
        long total = jdbc.queryForObject("SELECT count(*) " + filter, Long.class, ts, category, category);
        List<ChangesResponse.Change> changes = jdbc.query("""
            SELECT url, from_version, to_version, detected_at, status, summary->>'text' AS summary_text,
                   (summary->>'confidence')::float AS summary_confidence,
                   entities_added::text AS added, entities_removed::text AS removed, category_from, category_to
            """ + filter + " ORDER BY detected_at DESC, id DESC LIMIT ?",
            (rs, n) -> new ChangesResponse.Change(rs.getString("url"), rs.getInt("from_version"),
                rs.getInt("to_version"), rs.getTimestamp("detected_at").toInstant(), rs.getString("status"),
                rs.getString("summary_text") == null ? null
                    : new ChangesResponse.ChangeSummary(rs.getString("summary_text"), rs.getDouble("summary_confidence")),
                entities(rs.getString("added")), entities(rs.getString("removed")),
                rs.getString("category_from"), rs.getString("category_to")),
            ts, category, category, size);
        return ResponseEntity.ok(new ChangesResponse(from, category, total, changes));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> entities(String json) {
        return json == null ? List.of() : mapper.readValue(json, List.class);
    }
}
