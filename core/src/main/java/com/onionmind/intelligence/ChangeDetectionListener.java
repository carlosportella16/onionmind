package com.onionmind.intelligence;

import com.onionmind.content.ExtractedEntity;
import com.onionmind.ingestion.PageEnrichedEvent;
import com.onionmind.ingestion.PageQuarantinedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records one {@code page_changes} row per new enriched version — fast and LLM-free, since
 * it runs in the listener's own transaction (design D9); ChangeSummaryJob summarizes later.
 * Baseline is the newest archived version whose snapshot carries enrichment, so a version
 * that changed again before being enriched never skews the entity/category delta (D7).
 */
@Component
class ChangeDetectionListener {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final int minChangedChars;
    private final int maxTextChars;

    ChangeDetectionListener(JdbcTemplate jdbc,
                            @Value("${intelligence.diff.min-changed-chars:40}") int minChangedChars,
                            @Value("${intelligence.diff.max-text-chars:4000}") int maxTextChars) {
        this.jdbc = jdbc;
        this.minChangedChars = minChangedChars;
        this.maxTextChars = maxTextChars;
    }

    @ApplicationModuleListener
    void on(PageEnrichedEvent event) {
        if (event.version() <= 1) {
            return;
        }
        Optional<String> current = jdbc.queryForList(
            "SELECT extracted_text FROM pages WHERE id = ? AND version = ?", String.class,
            event.pageId(), event.version()).stream().findFirst();
        Optional<Baseline> baseline = baseline(event.pageId(), event.version());
        if (current.isEmpty() || baseline.isEmpty()) {
            return; // page already moved on (a newer event follows) or no history to diff against
        }
        Baseline base = baseline.get();

        SentenceDiff.Result text = SentenceDiff.diff(base.text(), current.get());
        Map<String, ExtractedEntity> now = byId(event.entities());
        Map<String, Map<String, String>> added = new LinkedHashMap<>();
        Map<String, Map<String, String>> removed = new LinkedHashMap<>(base.entities());
        if (base.enriched()) {
            now.forEach((id, e) -> {
                if (removed.remove(id) == null) {
                    added.put(id, Map.of("type", e.type().code(), "key", e.key(), "display", e.display()));
                }
            });
        }
        String categoryFrom = base.enriched() ? base.category() : null;
        boolean categoryChanged = base.enriched() && categoryFrom != null && !categoryFrom.equals(event.category());
        boolean trivial = added.isEmpty() && removed.isEmpty() && !categoryChanged
            && (text.isEmpty() || text.changedChars() < minChangedChars);

        jdbc.update("""
            INSERT INTO page_changes (page_id, url, from_version, to_version, added_text, removed_text,
                                      entities_added, entities_removed, category_from, category_to, status)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
            ON CONFLICT (page_id, to_version) DO NOTHING
            """, event.pageId(), event.url(), base.version(), event.version(),
            SentenceDiff.cap(text.added(), maxTextChars), SentenceDiff.cap(text.removed(), maxTextChars),
            json(added.values()), json(removed.values()), categoryFrom, event.category(),
            trivial ? "trivial" : "pending");
    }

    @ApplicationModuleListener
    void on(PageQuarantinedEvent event) {
        jdbc.update("DELETE FROM page_changes WHERE url = ?", event.url());
    }

    private Optional<Baseline> baseline(long pageId, int version) {
        List<Baseline> rows = jdbc.query("""
            SELECT version, extracted_text, entities::text AS entities, category->>'category' AS category,
                   (entities IS NOT NULL OR category IS NOT NULL OR summary IS NOT NULL) AS enriched
            FROM page_versions
            WHERE page_id = ? AND version < ?
            ORDER BY (entities IS NOT NULL OR category IS NOT NULL OR summary IS NOT NULL) DESC, version DESC
            LIMIT 1
            """, (rs, n) -> new Baseline(rs.getInt("version"), rs.getString("extracted_text"),
            entities(rs.getString("entities")), rs.getString("category"), rs.getBoolean("enriched")),
            pageId, version);
        return rows.stream().findFirst();
    }

    private Map<String, Map<String, String>> entities(String json) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (json == null) {
            return out;
        }
        for (JsonNode e : mapper.readTree(json)) {
            String type = e.path("type").asString();
            String key = e.path("key").asString();
            out.put(type + ":" + key, Map.of("type", type, "key", key, "display", e.path("display").asString(key)));
        }
        return out;
    }

    private static Map<String, ExtractedEntity> byId(List<ExtractedEntity> entities) {
        Map<String, ExtractedEntity> out = new LinkedHashMap<>();
        entities.forEach(e -> out.putIfAbsent(e.id(), e));
        return out;
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private record Baseline(int version, String text, Map<String, Map<String, String>> entities, String category,
                            boolean enriched) {}
}
