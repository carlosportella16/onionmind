package com.onionmind.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRankerTest {

    private SearchResult result(String url, double rank) {
        return new SearchResult(1L, url, "tor", "snippet", rank, 1, Instant.now(), Instant.now(), null, null);
    }

    @Test
    void urlPresentInBothListsIsNotDuplicated() {
        var text = List.of(result("http://a.onion", 0.5));
        var semantic = List.of(result("http://a.onion", 0.9));

        var fused = HybridRanker.fuse(text, semantic, 10);

        assertThat(fused).extracting(SearchResult::url).containsExactly("http://a.onion");
    }

    @Test
    void semanticOnlyHitSurvivesFusionEvenWithNoTextMatch() {
        var text = List.<SearchResult>of();
        var semantic = List.of(result("http://semantic-only.onion", 0.8));

        var fused = HybridRanker.fuse(text, semantic, 10);

        assertThat(fused).extracting(SearchResult::url).containsExactly("http://semantic-only.onion");
    }

    @Test
    void bestMatchInBothListsRanksAboveOnlyOneListMatch() {
        var text = List.of(result("http://both.onion", 1.0), result("http://text-only.onion", 1.0));
        var semantic = List.of(result("http://both.onion", 1.0));

        var fused = HybridRanker.fuse(text, semantic, 10);

        assertThat(fused.get(0).url()).isEqualTo("http://both.onion");
    }

    @Test
    void resultIsCappedAtLimit() {
        var text = List.of(result("http://a.onion", 1.0), result("http://b.onion", 0.5));
        var semantic = List.<SearchResult>of();

        var fused = HybridRanker.fuse(text, semantic, 1);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).url()).isEqualTo("http://a.onion");
    }
}
