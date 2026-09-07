package com.onionmind.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/** Basic CRUD, no UI (design.md Non-Goals) — administering rules is an API-only concern for now. */
@Repository
class AlertRuleRepository {

    private final JdbcTemplate jdbc;

    AlertRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Long create(AlertCriteriaType criteriaType, String criteriaValue) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO alert_rules (criteria_type, criteria_value) VALUES (?, ?)",
                new String[]{"id"});
            ps.setString(1, criteriaType.name());
            ps.setString(2, criteriaValue);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    List<AlertRule> findActive() {
        return jdbc.query("SELECT id, criteria_type, criteria_value, active FROM alert_rules WHERE active = true",
            (rs, rowNum) -> new AlertRule(
                rs.getLong("id"),
                AlertCriteriaType.valueOf(rs.getString("criteria_type")),
                rs.getString("criteria_value"),
                rs.getBoolean("active")));
    }
}
