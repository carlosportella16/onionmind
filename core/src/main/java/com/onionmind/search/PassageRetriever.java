package com.onionmind.search;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chat's retrieval (phase5-rag-chat D4), the search module's API for it: full-text with
 * the original question and with the rewritten query, vector search with the rewritten one,
 * fused by RRF; then one passage per page — its best vector chunk, or a ts_headline window
 * when it has none. The chat never touches Postgres or Qdrant itself (D1).
 */
@Component
public class PassageRetriever {

    /** One page's context for the answer: the page as a search result, and the passage cited from it. */
    public record Passage(SearchResult page, String text) {
    }

    /** {@code results} are the top-k fused pages; {@code passages} the ones that fit the context budget. */
    public record Retrieval(List<SearchResult> results, List<Passage> passages) {
    }

    private final FullTextSearch fullText;
    private final SemanticSearchService semantic;

    PassageRetriever(FullTextSearch fullText, SemanticSearchService semantic) {
        this.fullText = fullText;
        this.semantic = semantic;
    }

    /**
     * @param rewritten       the English search query, or null when rewriting failed
     * @param maxContextChars total passage text sent to the model; the last passage is cut to fit
     */
    public Retrieval retrieve(String original, String rewritten, int k, int maxContextChars) {
        String vectorQuery = rewritten == null ? original : rewritten;
        List<SearchResult> text = fullText.search(original, k, 0, FullTextSearch.PASSAGE);
        if (rewritten != null && !rewritten.equalsIgnoreCase(original)) {
            text = byBestPosition(text, fullText.search(rewritten, k, 0, FullTextSearch.PASSAGE));
        }
        Map<String, SemanticSearchHit> chunks = semantic.bestChunks(vectorQuery, k);
        List<SearchResult> results = ReciprocalRankFusion.fuse(text, semantic.hydrate(chunks), k);

        List<Passage> passages = new ArrayList<>();
        int budget = maxContextChars;
        for (SearchResult page : results) {
            if (budget <= 0) {
                break;
            }
            SemanticSearchHit chunk = chunks.get(page.url());
            String passage = chunk != null && chunk.text() != null ? chunk.text() : page.snippet();
            if (passage == null || passage.isBlank()) {
                continue;
            }
            passage = passage.length() > budget ? passage.substring(0, budget) : passage;
            budget -= passage.length();
            passages.add(new Passage(page, passage));
        }
        return new Retrieval(results, passages);
    }

    /** Union of two rankings by each page's best position; {@code a} wins ties. */
    private static List<SearchResult> byBestPosition(List<SearchResult> a, List<SearchResult> b) {
        Map<String, SearchResult> merged = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) merged.putIfAbsent(a.get(i).url(), a.get(i));
            if (i < b.size()) merged.putIfAbsent(b.get(i).url(), b.get(i));
        }
        return new ArrayList<>(merged.values());
    }
}
