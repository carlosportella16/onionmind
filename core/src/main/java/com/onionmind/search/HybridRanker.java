package com.onionmind.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fusão simples full-text + vetorial (roadmap Fase 2, master-sdd sec. 5). Não é RRF — isso
 * fica pra Fase 5 (hybrid retrieval + re-rank, master-sdd sec. 8.1). Cada lista é normalizada
 * pelo próprio máximo e somada, mantendo a melhor entrada por url.
 */
final class HybridRanker {

    private HybridRanker() {
    }

    static List<SearchResult> fuse(List<SearchResult> textResults, List<SearchResult> semanticResults, int limit) {
        Map<String, SearchResult> byUrl = new LinkedHashMap<>();
        Map<String, Double> textScore = new LinkedHashMap<>();
        Map<String, Double> semanticScore = new LinkedHashMap<>();

        double textMax = textResults.stream().mapToDouble(SearchResult::rank).max().orElse(0.0);
        double semanticMax = semanticResults.stream().mapToDouble(SearchResult::rank).max().orElse(0.0);

        for (SearchResult r : textResults) {
            byUrl.put(r.url(), r);
            textScore.put(r.url(), textMax > 0 ? r.rank() / textMax : 0.0);
        }
        for (SearchResult r : semanticResults) {
            byUrl.putIfAbsent(r.url(), r);
            semanticScore.put(r.url(), semanticMax > 0 ? r.rank() / semanticMax : 0.0);
        }

        List<SearchResult> fused = new ArrayList<>();
        for (var entry : byUrl.entrySet()) {
            String url = entry.getKey();
            SearchResult base = entry.getValue();
            double combined = textScore.getOrDefault(url, 0.0) + semanticScore.getOrDefault(url, 0.0);
            fused.add(new SearchResult(base.id(), base.url(), base.sourceType(), base.snippet(),
                combined, base.version(), base.firstSeenAt(), base.lastSeenAt(),
                base.summary(), base.category()));
        }

        fused.sort(Comparator.comparingDouble(SearchResult::rank).reversed());
        return fused.size() > limit ? fused.subList(0, limit) : fused;
    }
}
