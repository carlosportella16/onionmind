package com.onionmind.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The content_hash gate for AI processors (SDD sec. 6.5 / fase3-sdd 7.6): a page whose
 * content has not changed since it was last fully enriched must not be reprocessed —
 * that would spend free-tier quota for nothing. Mirrors what EmbeddingProcessor does
 * against Qdrant, but the check is a row in {@code pages}.
 */
@Component
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
public class AiEnrichmentGate {

    private final JdbcTemplate jdbc;

    public AiEnrichmentGate(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean alreadyEnriched(Document document) {
        String hash = document.resolvedContentHash();
        if (hash == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM pages
            WHERE url = ? AND content_hash = ? AND ai_status = 'processed'
            """, Integer.class, document.url(), hash);
        return count != null && count > 0;
    }
}
