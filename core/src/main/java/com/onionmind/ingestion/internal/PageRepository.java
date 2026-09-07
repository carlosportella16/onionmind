package com.onionmind.ingestion.internal;

import com.google.common.hash.Hashing;
import com.onionmind.content.AiOutcome;
import com.onionmind.content.Document;
import com.onionmind.content.EmbeddingOutcome;
import com.onionmind.content.Enrichment;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class PageRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public PageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertWithVersioning(Document doc) {
        upsertWithVersioning(doc, null);
    }

    /** @param embeddingOutcome null when EmbeddingProcessor isn't in this build's pipeline. */
    public void upsertWithVersioning(Document doc, EmbeddingOutcome embeddingOutcome) {
        String hash = sha256(doc.extractedText());
        var existing = findByUrl(doc.url());

        if (existing.isEmpty()) {
            insertNew(doc, hash);
        } else {
            var current = existing.get();
            if (!current.contentHash().equals(hash)) {
                // content changed: archive previous version, update
                archiveVersion(current);
                updateWithNewVersion(doc, hash, current.version() + 1);
            } else {
                // identical content: only touch last_seen_at
                touchLastSeen(current.id());
            }
        }

        if (embeddingOutcome != null) {
            updateEmbeddingStatus(doc.url(), embeddingOutcome);
        }
    }

    /**
     * A page the illegal-content guard blocked: record it for audit and make sure no
     * content is stored — strip an existing row, or insert a minimal one if none exists.
     */
    public void quarantine(Document doc, String reason) {
        String hash = sha256(doc.extractedText() == null ? "" : doc.extractedText());

        jdbc.update("""
            INSERT INTO quarantined_pages (url, content_hash, reason)
            VALUES (?, ?, ?)
            """, doc.url(), hash, reason);

        int updated = jdbc.update("""
            UPDATE pages
            SET raw_html = NULL, extracted_text = NULL,
                ai_status = 'quarantined', ai_processed_at = now()
            WHERE url = ?
            """, doc.url());

        if (updated == 0) {
            jdbc.update("""
                INSERT INTO pages (url, source_type, content_hash, ai_status)
                VALUES (?, ?, ?, 'quarantined')
                """, doc.url(), doc.sourceType(), hash);
        }
    }

    /** Writes the Fase 3 enrichment (summary/category/language/translation JSONB + ai_status). */
    public void updateAiFields(String url, AiOutcome outcome) {
        Enrichment e = outcome.enrichment();
        jdbc.update("""
            UPDATE pages
            SET summary = ?::jsonb, category = ?::jsonb, language = ?::jsonb, translated_text = ?::jsonb,
                ai_status = ?, ai_processed_at = now(), ai_error_message = ?
            WHERE url = ?
            """,
            summaryJson(e), categoryJson(e), languageJson(e), translationJson(e),
            outcome.status(), outcome.errorMessage(), url);
    }

    private String summaryJson(Enrichment e) {
        return e.summary() == null ? null
            : json(Map.of("text", e.summary().text(), "confidence", e.summary().confidence()));
    }

    private String categoryJson(Enrichment e) {
        return e.category() == null ? null
            : json(Map.of("category", e.category().category(), "confidence", e.category().confidence()));
    }

    private String languageJson(Enrichment e) {
        return e.language() == null ? null
            : json(Map.of("code", e.language().code(), "confidence", e.language().confidence()));
    }

    private String translationJson(Enrichment e) {
        if (e.translation() == null) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        map.put("text", e.translation().text());
        map.put("detectedLanguage", e.translation().detectedLanguage());
        map.put("confidence", e.translation().confidence());
        return json(map);
    }

    private String json(Map<String, ?> map) {
        return mapper.writeValueAsString(map);
    }

    private void updateEmbeddingStatus(String url, EmbeddingOutcome outcome) {
        jdbc.update("""
            UPDATE pages
            SET embedding_status = ?, embedding_attempted_at = now(), embedding_error_message = ?
            WHERE url = ?
            """, outcome.status(), outcome.errorMessage(), url);
    }

    private void insertNew(Document doc, String hash) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, raw_html, extracted_text, content_hash, version)
            VALUES (?, ?, ?, ?, ?, 1)
            """, doc.url(), doc.sourceType(), doc.rawHtml(), doc.extractedText(), hash);
    }

    private void archiveVersion(PageEntity current) {
        jdbc.update("""
            INSERT INTO page_versions (page_id, version, content_hash, extracted_text)
            VALUES (?, ?, ?, ?)
            """, current.id(), current.version(), current.contentHash(), current.extractedText());
    }

    private void updateWithNewVersion(Document doc, String hash, int newVersion) {
        // Reset ai_status/embedding_status to 'pending' so the backfill jobs pick this page
        // back up (fix-ingestion-stability: AI/embedding no longer run inline here, so nothing
        // else would ever re-enrich changed content — they only scan for pending/failed rows).
        // Without this, a page whose content changes keeps its old, now-stale enrichment
        // status forever.
        jdbc.update("""
            UPDATE pages
            SET extracted_text = ?, content_hash = ?, version = ?,
                raw_html = ?, last_seen_at = now(),
                ai_status = 'pending', embedding_status = 'pending'
            WHERE url = ?
            """, doc.extractedText(), hash, newVersion, doc.rawHtml(), doc.url());
    }

    private void touchLastSeen(Long id) {
        jdbc.update("UPDATE pages SET last_seen_at = now() WHERE id = ?", id);
    }

    private Optional<PageEntity> findByUrl(String url) {
        try {
            return Optional.of(jdbc.queryForObject(
                "SELECT id, url, content_hash, version, extracted_text FROM pages WHERE url = ?",
                (rs, rowNum) -> new PageEntity(
                    rs.getLong("id"), rs.getString("url"), rs.getString("content_hash"),
                    rs.getInt("version"), rs.getString("extracted_text")),
                url));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String sha256(String text) {
        return Hashing.sha256().hashString(text, StandardCharsets.UTF_8).toString();
    }
}
