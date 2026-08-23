package com.onionmind.search;

import com.onionmind.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class SearchControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String baseUrl() {
        return "http://localhost:" + port + "/api";
    }

    private void insertPage(String url, String text) {
        String hash = Integer.toHexString(text.hashCode());
        jdbc.update("""
            INSERT INTO pages (url, source_type, raw_html, extracted_text, content_hash, version)
            VALUES (?, 'tor', ?, ?, ?, 1)
            """, url, "<html>" + text + "</html>", text, hash);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM page_versions");
        jdbc.update("DELETE FROM pages");
    }

    @Test
    void searchReturnsRankedResultsForMatchingTerm() {
        insertPage("http://bitcoin.onion/", "bitcoin forum for anonymous trading and discussion");
        insertPage("http://other.onion/", "completely unrelated content about gardening");

        var response = rest.getForEntity(baseUrl() + "/search?q=bitcoin", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().results()).extracting(SearchResult::url)
            .contains("http://bitcoin.onion/");
        assertThat(response.getBody().total()).isEqualTo(1);
    }

    @Test
    void blankQueryReturnsBadRequest() {
        var response = rest.getForEntity(baseUrl() + "/search?q=", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void paginationRespectsPageAndSize() {
        for (int i = 0; i < 5; i++) {
            insertPage("http://paged" + i + ".onion/", "bitcoin content page " + i);
        }

        var response = rest.getForEntity(baseUrl() + "/search?q=bitcoin&page=1&size=2", SearchResponse.class);

        assertThat(response.getBody().results()).hasSize(2);
        assertThat(response.getBody().page()).isEqualTo(1);
        assertThat(response.getBody().size()).isEqualTo(2);
    }

    @Test
    void sizeIsCappedAt100() {
        insertPage("http://one.onion/", "bitcoin content here");

        var response = rest.getForEntity(baseUrl() + "/search?q=bitcoin&size=500", SearchResponse.class);

        assertThat(response.getBody().size()).isEqualTo(100);
    }

    @Test
    void noResultsReturnsEmptyListNotError() {
        insertPage("http://one.onion/", "nothing relevant in here at all");

        var response = rest.getForEntity(baseUrl() + "/search?q=nonexistentterm12345", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().results()).isEmpty();
        assertThat(response.getBody().total()).isEqualTo(0);
    }

    @Test
    void semanticEndpointReturns503WhenEmbeddingIsDisabled() {
        var response = rest.getForEntity(baseUrl() + "/search/semantic?q=bitcoin", SearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
