package com.onionmind.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Non-trivial changes from {@code page_changes}, newest first — the module's API for
 * {@code /api/changes} and for the agents' {@code changes} tool (phase5-agent-playground D1).
 * Arguments are trusted: callers validate the window and the categories first.
 */
@Component
public class ChangeQuery {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();

    ChangeQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param categories     {@code category_to} codes to keep; empty keeps every category
     * @param summarizedOnly only changes that already have a summary
     */
    public ChangesResponse find(Instant since, Collection<String> categories, boolean summarizedOnly, int limit) {
        StringBuilder filter = new StringBuilder(" FROM page_changes WHERE status <> 'trivial' AND detected_at >= ?");
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(since)));
        if (!categories.isEmpty()) {
            filter.append(" AND category_to IN (").append(String.join(",", categories.stream().map(c -> "?").toList()))
                .append(')');
            args.addAll(categories);
        }
        if (summarizedOnly) {
            filter.append(" AND summary IS NOT NULL");
        }
        long total = jdbc.queryForObject("SELECT count(*)" + filter, Long.class, args.toArray());
        args.add(limit);
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
            args.toArray());
        return new ChangesResponse(since, categories.size() == 1 ? categories.iterator().next() : null, total, changes);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> entities(String json) {
        return json == null ? List.of() : mapper.readValue(json, List.class);
    }
}
