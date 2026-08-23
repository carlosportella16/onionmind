package com.onionmind.search;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.TaskContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Query-time half of Fase 2 semantic search (master-sdd sec. 8.1): embeds the query, asks
 * Qdrant for the nearest chunks, then re-hydrates page metadata from Postgres — Qdrant only
 * ever stores url + chunk_index, never the page's own data.
 *
 * VectorStore/AIOrchestrator are optional because both are @ConditionalOnProperty on
 * embedding.enabled — with it off (Fase 1 behavior), no bean of either type exists.
 */
@Component
public class SemanticSearchService {

    private static final int SNIPPET_LENGTH = 240;

    private final Optional<VectorStore> vectorStore;
    private final Optional<AIOrchestrator> aiOrchestrator;
    private final JdbcTemplate jdbc;

    public SemanticSearchService(Optional<VectorStore> vectorStore,
                                  Optional<AIOrchestrator> aiOrchestrator,
                                  JdbcTemplate jdbc) {
        this.vectorStore = vectorStore;
        this.aiOrchestrator = aiOrchestrator;
        this.jdbc = jdbc;
    }

    public boolean isAvailable() {
        return vectorStore.isPresent() && aiOrchestrator.isPresent();
    }

    public List<SearchResult> search(String query, int topK) {
        if (!isAvailable()) {
            return List.of();
        }

        var ctx = new TaskContext(TaskContext.TaskType.EMBED, query.length() / 4, null, false, true, null, 0);
        float[] queryVector = aiOrchestrator.get().embed(query, ctx).vector();

        List<SemanticSearchHit> hits = vectorStore.get().search(queryVector, topK);
        if (hits.isEmpty()) {
            return List.of();
        }

        // A page can match on more than one chunk — keep its best score.
        Map<String, Double> bestScoreByUrl = new LinkedHashMap<>();
        for (SemanticSearchHit hit : hits) {
            bestScoreByUrl.merge(hit.url(), hit.score(), Math::max);
        }

        return hydrate(bestScoreByUrl);
    }

    private List<SearchResult> hydrate(Map<String, Double> scoreByUrl) {
        List<String> urls = new ArrayList<>(scoreByUrl.keySet());
        String placeholders = String.join(",", urls.stream().map(u -> "?").toList());

        List<SearchResult> rows = jdbc.query("""
            SELECT id, url, source_type, extracted_text, version, first_seen_at, last_seen_at
            FROM pages
            WHERE url IN (%s)
            """.formatted(placeholders),
            (rs, rowNum) -> {
                String url = rs.getString("url");
                return new SearchResult(
                    rs.getLong("id"), url, rs.getString("source_type"),
                    snippetOf(rs.getString("extracted_text")),
                    scoreByUrl.get(url),
                    rs.getInt("version"),
                    rs.getTimestamp("first_seen_at").toInstant(),
                    rs.getTimestamp("last_seen_at").toInstant()
                );
            }, urls.toArray());

        return rows.stream()
            .sorted((a, b) -> Double.compare(b.rank(), a.rank()))
            .toList();
    }

    private String snippetOf(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH) + "…";
    }
}
