package com.onionmind.search;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.search.PassageRetriever.Passage;
import com.onionmind.search.SemanticSearchServiceTest.FakeAIOrchestrator;
import com.onionmind.search.SemanticSearchServiceTest.FakeVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PassageRetrieverTest {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
    }

    private void insertPage(String url, String text) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, raw_html, extracted_text, content_hash, version)
            VALUES (?, 'tor', ?, ?, ?, 1)
            """, url, "<html>" + text + "</html>", text, Integer.toHexString(text.hashCode()));
    }

    private PassageRetriever retriever(SemanticSearchHit... hits) {
        var semantic = new SemanticSearchService(Optional.of(new FakeVectorStore(List.of(hits))),
            Optional.of(new FakeAIOrchestrator()), jdbc, 0.5);
        return new PassageRetriever(new FullTextSearch(jdbc), semantic);
    }

    @Test
    void pageWithAVectorChunkIsCitedByThatChunk() {
        insertPage("http://passports.onion/", "Shop selling fake passports and other forged documents worldwide.");

        var retrieval = retriever(new SemanticSearchHit("http://passports.onion/", 2, 0.9, "the best chunk"))
            .retrieve("documentos falsos", "fake passports", 8, 12000);

        assertThat(retrieval.passages()).singleElement().satisfies(p -> {
            assertThat(p.page().url()).isEqualTo("http://passports.onion/");
            assertThat(p.text()).isEqualTo("the best chunk");
        });
    }

    @Test
    void pageWithoutAChunkTextIsCitedByAHeadlineWindowAsPlainText() {
        insertPage("http://forum.onion/", "A long forum thread where members discuss xylophone repairs at length.");

        var retrieval = retriever(new SemanticSearchHit("http://forum.onion/", 0, 0.9, null))
            .retrieve("xylophone", null, 8, 12000);

        assertThat(retrieval.passages()).singleElement().extracting(Passage::text).asString()
            .contains("xylophone repairs").doesNotContain("<b>");
    }

    @Test
    void rewrittenQueryFindsWhatTheOriginalQuestionMisses() {
        insertPage("http://passports.onion/", "Shop selling fake passports and other forged documents worldwide.");

        assertThat(retriever().retrieve("quais mercados vendem passaportes?", null, 8, 12000).results()).isEmpty();
        assertThat(retriever().retrieve("quais mercados vendem passaportes?", "fake passports", 8, 12000).results())
            .extracting(SearchResult::url).containsExactly("http://passports.onion/");
    }

    @Test
    void contextIsCutAtMaxContextChars() {
        insertPage("http://a.onion/", "first page");
        insertPage("http://b.onion/", "second page");
        insertPage("http://c.onion/", "third page");

        var retrieval = retriever(
            new SemanticSearchHit("http://a.onion/", 0, 0.9, "a".repeat(60)),
            new SemanticSearchHit("http://b.onion/", 0, 0.8, "b".repeat(60)),
            new SemanticSearchHit("http://c.onion/", 0, 0.7, "c".repeat(60))
        ).retrieve("anything", "anything", 8, 100);

        assertThat(retrieval.results()).hasSize(3);
        assertThat(retrieval.passages()).extracting(Passage::text).containsExactly("a".repeat(60), "b".repeat(40));
    }
}
