package com.onionmind.intelligence;

import com.onionmind.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AlertRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private AlertRepository repository;
    private AlertRuleRepository ruleRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM alert_rules");
        repository = new AlertRepository(jdbc);
        ruleRepository = new AlertRuleRepository(jdbc);
    }

    private Long insertPage(String url) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, extracted_text, content_hash, version)
            VALUES (?, 'tor', 'text', 'hash', 1)
            """, url);
        return jdbc.queryForObject("SELECT id FROM pages WHERE url = ?", Long.class, url);
    }

    @Test
    void recordAndFindAllRoundTrips() {
        Long ruleId = ruleRepository.create(AlertCriteriaType.KEYWORD, "bitcoin");
        Long pageId = insertPage("http://alert-" + System.nanoTime() + ".onion");

        repository.record(ruleId, pageId, 1);

        assertThat(repository.findAll()).hasSize(1);
        var view = repository.findAll().getFirst();
        assertThat(view.criteriaType()).isEqualTo(AlertCriteriaType.KEYWORD);
        assertThat(view.criteriaValue()).isEqualTo("bitcoin");
        assertThat(view.pageVersion()).isEqualTo(1);
    }

    @Test
    void recordingTheSameAlertTwiceIsIdempotent() {
        Long ruleId = ruleRepository.create(AlertCriteriaType.KEYWORD, "bitcoin");
        Long pageId = insertPage("http://alert-dup-" + System.nanoTime() + ".onion");

        repository.record(ruleId, pageId, 1);
        repository.record(ruleId, pageId, 1); // simulated event redelivery

        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM alerts WHERE rule_id = ? AND page_id = ? AND page_version = 1",
            Integer.class, ruleId, pageId);
        assertThat(count).isEqualTo(1);
    }
}
