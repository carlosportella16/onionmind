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
class AlertRuleRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    private AlertRuleRepository repository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM alerts");
        jdbc.update("DELETE FROM alert_rules");
        repository = new AlertRuleRepository(jdbc);
    }

    @Test
    void createPersistsAnActiveRuleByDefault() {
        Long id = repository.create(AlertCriteriaType.KEYWORD, "bitcoin");

        assertThat(id).isNotNull();
        assertThat(repository.findActive()).containsExactly(
            new AlertRule(id, AlertCriteriaType.KEYWORD, "bitcoin", true));
    }

    @Test
    void findActiveOnlyReturnsActiveRules() {
        repository.create(AlertCriteriaType.KEYWORD, "bitcoin");
        jdbc.update("UPDATE alert_rules SET active = false WHERE criteria_value = 'bitcoin'");

        assertThat(repository.findActive()).isEmpty();
    }

    @Test
    void findActiveReturnsMultipleCriteriaTypes() {
        repository.create(AlertCriteriaType.KEYWORD, "bitcoin");
        repository.create(AlertCriteriaType.CATEGORY, "forum");
        repository.create(AlertCriteriaType.ENTITY_TYPE, "CRYPTO_WALLET");

        assertThat(repository.findActive()).hasSize(3);
    }
}
