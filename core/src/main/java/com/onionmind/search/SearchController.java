package com.onionmind.search;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class SearchController {
    private final JdbcTemplate jdbc;
    private final SemanticSearchService semanticSearchService;
    private final FullTextSearch fullText;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SearchController(JdbcTemplate jdbc, SemanticSearchService semanticSearchService, FullTextSearch fullText) {
        this.jdbc = jdbc;
        this.semanticSearchService = semanticSearchService;
        this.fullText = fullText;
    }

    @PreDestroy
    void shutdown() {
        executor.close();
    }

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (q.isBlank()) return ResponseEntity.badRequest().build();
        size = Math.min(size, 100); // never more than 100 per page
        int effectiveSize = size;
        int offset = page * effectiveSize;

        // Full-text e vetorial em paralelo — latência é a mais lenta das duas, não a soma
        // (master-sdd sec. 9).
        var textFuture = CompletableFuture.supplyAsync(
            () -> fullText.search(q, effectiveSize, offset, FullTextSearch.SNIPPET), executor);
        var totalFuture = CompletableFuture.supplyAsync(() -> fullText.total(q), executor);
        var semanticFuture = semanticSearchService.isAvailable()
            ? CompletableFuture.supplyAsync(() -> semanticSearchService.search(q, effectiveSize), executor)
            : CompletableFuture.completedFuture(List.<SearchResult>of());

        var textResults = textFuture.join();
        var semanticResults = semanticFuture.join();
        long textTotal = totalFuture.join();

        if (semanticResults.isEmpty()) {
            return ResponseEntity.ok(new SearchResponse(textResults, textTotal, page, size));
        }

        // RRF k=60 (phase5-rag-chat D2) só combina as duas fontes na primeira página; as
        // seguintes seguem só com o full-text, como antes — o contrato não muda.
        if (page > 0) {
            return ResponseEntity.ok(new SearchResponse(textResults, textTotal, page, size));
        }

        List<SearchResult> fused = ReciprocalRankFusion.fuse(textResults, semanticResults, effectiveSize);
        long fusedTotal = unionSize(textResults, semanticResults);
        return ResponseEntity.ok(new SearchResponse(fused, fusedTotal, page, size));
    }

    @GetMapping("/search/semantic")
    public ResponseEntity<SearchResponse> searchSemantic(
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int size) {

        if (q.isBlank()) return ResponseEntity.badRequest().build();
        if (!semanticSearchService.isAvailable()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();

        size = Math.min(size, 100);
        var results = semanticSearchService.search(q, size);
        return ResponseEntity.ok(new SearchResponse(results, results.size(), 0, size));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
            "total_pages", jdbc.queryForObject("SELECT count(*) FROM pages", Long.class),
            "total_versions", jdbc.queryForObject("SELECT count(*) FROM page_versions", Long.class),
            "sources", jdbc.queryForList("SELECT source_type, count(*) as n FROM pages GROUP BY source_type")
        );
    }

    private long unionSize(List<SearchResult> a, List<SearchResult> b) {
        Set<String> urls = new HashSet<>();
        a.forEach(r -> urls.add(r.url()));
        b.forEach(r -> urls.add(r.url()));
        return urls.size();
    }
}
