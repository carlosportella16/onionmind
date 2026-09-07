package com.onionmind.intelligence;

import com.onionmind.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DiffControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/pages";
    }

    private Long insertPageWithDiff(String url, String diffText) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version)
            VALUES (?, 'tor', 'text', 'hash', 2)
            """, url);
        Long pageId = jdbc.queryForObject("SELECT id FROM pages WHERE url = ?", Long.class, url);
        jdbc.update("""
            INSERT INTO page_diffs (page_id, from_version, to_version, summary)
            VALUES (?, 1, 2, ?::jsonb)
            """, pageId, "{\"text\": \"" + diffText + "\", \"confidence\": 0.9}");
        return pageId;
    }

    @Test
    void returnsTheLatestDiffForAPage() {
        String url = "http://diff-endpoint-" + System.nanoTime() + ".onion";
        insertPageWithDiff(url, "mudou algo importante");

        var response = rest.getForEntity(baseUrl() + "/diff?url=" + url, PageDiffView.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().text()).isEqualTo("mudou algo importante");
    }

    @Test
    void returnsNotFoundWhenPageHasNoDiff() {
        var response = rest.getForEntity(
            baseUrl() + "/diff?url=http://never-diffed-" + System.nanoTime() + ".onion", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void blankUrlIsABadRequest() {
        var response = rest.getForEntity(baseUrl() + "/diff?url=", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
