package com.onionmind.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion of the full-text and vector lists (phase5-rag-chat D2, master-sdd
 * sec. 8.1): each page scores {@code Σ 1/(K + rank)} over the lists it appears in, rank from 1.
 * Only positions count, never raw scores — so ts_rank not being BM25 doesn't matter. The
 * full-text entry is kept when a page is in both lists (it carries the ts_headline snippet).
 */
final class ReciprocalRankFusion {

    static final int K = 60;

    private ReciprocalRankFusion() {
    }

    static List<SearchResult> fuse(List<SearchResult> textResults, List<SearchResult> semanticResults, int limit) {
        Map<String, SearchResult> byUrl = new LinkedHashMap<>();
        Map<String, Double> score = new LinkedHashMap<>();
        for (List<SearchResult> list : List.of(textResults, semanticResults)) {
            for (int i = 0; i < list.size(); i++) {
                SearchResult r = list.get(i);
                byUrl.putIfAbsent(r.url(), r);
                score.merge(r.url(), 1.0 / (K + i + 1), Double::sum);
            }
        }

        List<SearchResult> fused = new ArrayList<>();
        byUrl.forEach((url, base) -> fused.add(new SearchResult(base.id(), base.url(), base.sourceType(),
            base.snippet(), score.get(url), base.version(), base.firstSeenAt(), base.lastSeenAt(),
            base.summary(), base.category())));

        fused.sort(Comparator.comparingDouble(SearchResult::rank).reversed());
        return fused.size() > limit ? fused.subList(0, limit) : fused;
    }
}
