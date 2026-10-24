package com.onionmind.search;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReciprocalRankFusionTest {

    private SearchResult result(String url, double rank) {
        return new SearchResult(1L, url, "tor", "snippet", rank, 1, Instant.now(), Instant.now(), null, null);
    }

    @Test
    void pageInBothListsBeatsOneThatOnlyTopsOneList() {
        var text = List.of(result("http://top-text.onion", 0.9), result("http://x.onion", 0.8),
            result("http://both.onion", 0.7));
        var semantic = List.of(result("http://y.onion", 0.9), result("http://both.onion", 0.8));

        var fused = ReciprocalRankFusion.fuse(text, semantic, 10);

        // 1/63 + 1/62 > 1/61
        assertThat(fused.get(0).url()).isEqualTo("http://both.onion");
        assertThat(fused.get(0).rank()).isEqualTo(1.0 / 63 + 1.0 / 62);
        assertThat(fused).extracting(SearchResult::url).containsSubsequence("http://both.onion", "http://top-text.onion");
    }

    @Test
    void rawScoresDoNotChangeTheOrder() {
        var text = List.of(result("http://a.onion", 0.01), result("http://b.onion", 0.009));
        var semantic = List.of(result("http://a.onion", 0.99), result("http://b.onion", 0.2));
        var otherText = List.of(result("http://a.onion", 5.0), result("http://b.onion", 0.1));
        var otherSemantic = List.of(result("http://a.onion", 0.51), result("http://b.onion", 0.5));

        assertThat(ReciprocalRankFusion.fuse(text, semantic, 10)).extracting(SearchResult::url)
            .isEqualTo(ReciprocalRankFusion.fuse(otherText, otherSemantic, 10).stream().map(SearchResult::url).toList())
            .containsExactly("http://a.onion", "http://b.onion");
    }

    @Test
    void withNoVectorHitsTheResultIsTheFullTextOrder() {
        var text = List.of(result("http://a.onion", 0.1), result("http://b.onion", 0.5), result("http://c.onion", 0.9));

        assertThat(ReciprocalRankFusion.fuse(text, List.of(), 10)).extracting(SearchResult::url)
            .containsExactly("http://a.onion", "http://b.onion", "http://c.onion");
    }

    @Test
    void urlPresentInBothListsIsNotDuplicatedAndKeepsTheFullTextSnippet() {
        var text = List.of(new SearchResult(1L, "http://a.onion", "tor", "headline", 0.5, 1, Instant.now(),
            Instant.now(), null, null));
        var semantic = List.of(result("http://a.onion", 0.9));

        var fused = ReciprocalRankFusion.fuse(text, semantic, 10);

        assertThat(fused).singleElement().extracting(SearchResult::snippet).isEqualTo("headline");
    }

    @Test
    void semanticOnlyHitSurvivesFusionEvenWithNoTextMatch() {
        var fused = ReciprocalRankFusion.fuse(List.of(), List.of(result("http://semantic-only.onion", 0.8)), 10);

        assertThat(fused).extracting(SearchResult::url).containsExactly("http://semantic-only.onion");
    }

    @Test
    void resultIsCappedAtLimit() {
        var text = List.of(result("http://a.onion", 1.0), result("http://b.onion", 0.5));

        var fused = ReciprocalRankFusion.fuse(text, List.of(), 1);

        assertThat(fused).extracting(SearchResult::url).containsExactly("http://a.onion");
    }
}
