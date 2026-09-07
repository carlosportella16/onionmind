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
class AlertsControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private String baseUrl() {
        return "http://localhost:" + port + "/api/alerts";
    }

    @Test
    void listsTriggeredAlerts() {
        var ruleRepository = new AlertRuleRepository(jdbc);
        var alertRepository = new AlertRepository(jdbc);
        String url = "http://alert-endpoint-" + System.nanoTime() + ".onion";
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version)
            VALUES (?, 'tor', 'text', 'hash', 1)
            """, url);
        Long pageId = jdbc.queryForObject("SELECT id FROM pages WHERE url = ?", Long.class, url);
        Long ruleId = ruleRepository.create(AlertCriteriaType.KEYWORD, "monero");
        alertRepository.record(ruleId, pageId, 1);

        var response = rest.getForEntity(baseUrl(), AlertRepository.AlertView[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(AlertRepository.AlertView::pageUrl).contains(url);
    }
}
