package com.onionmind.search;

import com.onionmind.ai.AIOrchestrator;
import com.onionmind.ai.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

    private static final Logger log = LoggerFactory.getLogger(SemanticSearchService.class);
    private static final int SNIPPET_LENGTH = 240;
    // Qdrant's topK is chunk-level, not page-level: a single page with more chunks than topK
    // can occupy the entire candidate window, making every other page structurally unreachable
    // regardless of min-score (found live — one page with 427 of 454 total chunks crowded out
    // a genuinely relevant page at every topK up to 100). Over-fetch a wider raw candidate pool,
    // dedupe by url, then trim to what the caller asked for.
    // ponytail: fixed multiplier/cap, not adaptive to corpus shape — raise MAX_CANDIDATES (or
    // move to Qdrant's group-by-payload query) if a single page's chunk count outgrows this too.
    private static final int CANDIDATE_MULTIPLIER = 10;
    private static final int MAX_CANDIDATES = 500;

    private final Optional<VectorStore> vectorStore;
    private final Optional<AIOrchestrator> aiOrchestrator;
    private final JdbcTemplate jdbc;
    private final double minScore;

    public SemanticSearchService(Optional<VectorStore> vectorStore,
                                  Optional<AIOrchestrator> aiOrchestrator,
                                  JdbcTemplate jdbc,
                                  @Value("${embedding.min-score:0.5}") double minScore) {
        this.vectorStore = vectorStore;
        this.aiOrchestrator = aiOrchestrator;
        this.jdbc = jdbc;
        this.minScore = minScore;
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

        int candidatePool = Math.min(topK * CANDIDATE_MULTIPLIER, MAX_CANDIDATES);
        List<SemanticSearchHit> hits = vectorStore.get().search(queryVector, candidatePool);

        // DEBUG on this logger dumps every raw cosine score — how min-score gets calibrated
        // against a real corpus (task 1.2 of phase3-ai-generation).
        if (log.isDebugEnabled()) {
            hits.forEach(h -> log.debug("semantic-score q=\"{}\" score={} url={} chunk={}",
                query, h.score(), h.url(), h.chunkIndex()));
        }

        // Qdrant's k-NN always returns topK nearest points, even when nothing is actually
        // relevant (e.g. a small corpus) — drop anything below the relevance floor.
        Map<String, Double> bestScoreByUrl = new LinkedHashMap<>();
        for (SemanticSearchHit hit : hits) {
            if (hit.score() >= minScore) {
                bestScoreByUrl.merge(hit.url(), hit.score(), Math::max);
            }
        }
        if (bestScoreByUrl.isEmpty()) {
            return List.of();
        }

        return hydrate(bestScoreByUrl).stream().limit(topK).toList();
    }

    private List<SearchResult> hydrate(Map<String, Double> scoreByUrl) {
        List<String> urls = new ArrayList<>(scoreByUrl.keySet());
        String placeholders = String.join(",", urls.stream().map(u -> "?").toList());

        List<SearchResult> rows = jdbc.query("""
            SELECT id, url, source_type, extracted_text, version, first_seen_at, last_seen_at,
                   summary->>'text' AS summary, category->>'category' AS category
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
                    rs.getTimestamp("last_seen_at").toInstant(),
                    rs.getString("summary"), rs.getString("category")
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
