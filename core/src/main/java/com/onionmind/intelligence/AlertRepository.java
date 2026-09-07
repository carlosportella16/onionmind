package com.onionmind.intelligence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** {@code ON CONFLICT DO NOTHING} makes recording idempotent against event redelivery (ADR-009). */
@Repository
class AlertRepository {

    private final JdbcTemplate jdbc;

    AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void record(Long ruleId, Long pageId, int pageVersion) {
        jdbc.update("""
            INSERT INTO alerts (rule_id, page_id, page_version)
            VALUES (?, ?, ?)
            ON CONFLICT (rule_id, page_id, page_version) DO NOTHING
            """, ruleId, pageId, pageVersion);
    }

    List<AlertView> findAll() {
        return jdbc.query("""
            SELECT a.id, ar.criteria_type, ar.criteria_value, p.url AS page_url,
                   a.page_version, a.triggered_at
            FROM alerts a
            JOIN alert_rules ar ON ar.id = a.rule_id
            JOIN pages p ON p.id = a.page_id
            ORDER BY a.triggered_at DESC
            """, (rs, rowNum) -> new AlertView(
                rs.getLong("id"),
                AlertCriteriaType.valueOf(rs.getString("criteria_type")),
                rs.getString("criteria_value"),
                rs.getString("page_url"),
                rs.getInt("page_version"),
                rs.getTimestamp("triggered_at").toInstant()));
    }

    record AlertView(Long id, AlertCriteriaType criteriaType, String criteriaValue,
                      String pageUrl, int pageVersion, Instant triggeredAt) {
    }
}
