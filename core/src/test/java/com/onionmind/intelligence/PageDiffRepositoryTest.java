package com.onionmind.intelligence;

import com.onionmind.TestcontainersConfiguration;
import com.onionmind.ai.Summary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PageDiffRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private PageDiffRepository repository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        repository = new PageDiffRepository(jdbc);
    }

    private Long insertPage(String url) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version)
            VALUES (?, 'tor', 'text', 'hash', 2)
            """, url);
        return jdbc.queryForObject("SELECT id FROM pages WHERE url = ?", Long.class, url);
    }

    @Test
    void savePersistsSummaryAsJsonb() {
        String url = "http://diff-" + System.nanoTime() + ".onion";
        Long pageId = insertPage(url);

        repository.save(pageId, 1, 2, new Summary("mudou algo", 0.9));

        var diff = repository.findLatestByUrl(url).orElseThrow();
        assertThat(diff.fromVersion()).isEqualTo(1);
        assertThat(diff.toVersion()).isEqualTo(2);
        assertThat(diff.text()).isEqualTo("mudou algo");
        assertThat(diff.confidence()).isEqualTo(0.9);
    }

    @Test
    void findLatestByUrlReturnsEmptyWhenNoDiffExists() {
        String url = "http://no-diff-" + System.nanoTime() + ".onion";
        insertPage(url);

        assertThat(repository.findLatestByUrl(url)).isEmpty();
    }

    @Test
    void findLatestByUrlReturnsTheMostRecentTransition() {
        String url = "http://multi-diff-" + System.nanoTime() + ".onion";
        Long pageId = insertPage(url);
        repository.save(pageId, 1, 2, new Summary("primeira mudanca", 0.8));
        repository.save(pageId, 2, 3, new Summary("segunda mudanca", 0.9));

        var latest = repository.findLatestByUrl(url).orElseThrow();
        assertThat(latest.toVersion()).isEqualTo(3);
        assertThat(latest.text()).isEqualTo("segunda mudanca");
    }

    @Test
    void savingTheSameTransitionTwiceIsIdempotent() {
        String url = "http://redelivered-" + System.nanoTime() + ".onion";
        Long pageId = insertPage(url);

        repository.save(pageId, 1, 2, new Summary("primeira tentativa", 0.7));
        repository.save(pageId, 1, 2, new Summary("segunda tentativa (evento redelivered)", 0.9));

        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM page_diffs WHERE page_id = ? AND from_version = 1 AND to_version = 2",
            Integer.class, pageId);
        assertThat(count).isEqualTo(1);
        assertThat(repository.findLatestByUrl(url).orElseThrow().text()).isEqualTo("primeira tentativa");
    }
}
