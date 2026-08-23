package com.onionmind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * master-sdd sec. 9: "statement_timeout de ~2s no datasource da API evita query patológica
 * travando a request." Fase 2 made this concrete — full-text and vector search now run as
 * two parallel queries per request (SearchController) instead of one.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StatementTimeoutTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void pathologicalQueryIsCancelledAroundTwoSecondsNotLeftToHang() {
        Instant start = Instant.now();

        assertThatThrownBy(() -> jdbc.execute("SELECT pg_sleep(10)"))
            .isInstanceOf(DataAccessException.class)
            .rootCause()
            .hasMessageContaining("canceling statement due to statement timeout");

        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }
}
