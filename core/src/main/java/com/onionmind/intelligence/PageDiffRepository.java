package com.onionmind.intelligence;

import com.onionmind.ai.Summary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Optional;

/**
 * Owns {@code page_diffs} entirely within this module — no other module reads or writes it.
 * {@code ON CONFLICT DO NOTHING} makes {@link #save} idempotent against event redelivery
 * (ADR-009 is at-least-once; design.md D6).
 */
@Repository
class PageDiffRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    PageDiffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void save(Long pageId, int fromVersion, int toVersion, Summary summary) {
        jdbc.update("""
            INSERT INTO page_diffs (page_id, from_version, to_version, summary)
            VALUES (?, ?, ?, ?::jsonb)
            ON CONFLICT (page_id, from_version, to_version) DO NOTHING
            """, pageId, fromVersion, toVersion, toJson(summary));
    }

    Optional<PageDiffView> findLatestByUrl(String url) {
        try {
            return Optional.of(jdbc.queryForObject("""
                SELECT pd.from_version, pd.to_version,
                       pd.summary->>'text' AS text,
                       (pd.summary->>'confidence')::double precision AS confidence,
                       pd.generated_at
                FROM page_diffs pd JOIN pages p ON p.id = pd.page_id
                WHERE p.url = ?
                ORDER BY pd.to_version DESC
                LIMIT 1
                """, (rs, rowNum) -> new PageDiffView(
                    rs.getInt("from_version"), rs.getInt("to_version"),
                    rs.getString("text"), rs.getDouble("confidence"),
                    rs.getTimestamp("generated_at").toInstant()),
                url));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String toJson(Summary summary) {
        return mapper.writeValueAsString(Map.of("text", summary.text(), "confidence", summary.confidence()));
    }
}
