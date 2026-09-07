package com.onionmind.ingestion.internal;

import com.google.common.hash.Hashing;
import com.onionmind.content.AiOutcome;
import com.onionmind.content.Document;
import com.onionmind.content.EmbeddingOutcome;
import com.onionmind.content.Enrichment;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
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

    /**
     * @param embeddingOutcome null when EmbeddingProcessor isn't in this build's pipeline.
     * @return the page id/version and whether this call changed the content (Phase 4:
     *         {@link com.onionmind.ingestion.IngestionPipeline} uses this to publish
     *         {@code PageIndexedEvent}).
     */
    public UpsertOutcome upsertWithVersioning(Document doc, EmbeddingOutcome embeddingOutcome) {
        String hash = sha256(doc.extractedText());
        var existing = findByUrl(doc.url());

        Long pageId;
        int version;
        boolean isNewVersion;

        if (existing.isEmpty()) {
            pageId = insertNew(doc, hash);
            version = 1;
            isNewVersion = true;
        } else {
            var current = existing.get();
            pageId = current.id();
            if (!current.contentHash().equals(hash)) {
                // content changed: archive previous version, update
                archiveVersion(current);
                version = current.version() + 1;
                updateWithNewVersion(doc, hash, version);
                isNewVersion = true;
            } else {
                // identical content: only touch last_seen_at
                touchLastSeen(current.id());
                version = current.version();
                isNewVersion = false;
            }
        }

        if (embeddingOutcome != null) {
            updateEmbeddingStatus(doc.url(), embeddingOutcome);
        }

        return new UpsertOutcome(pageId, version, isNewVersion);
    }

    /** The current extracted text of a page, if it exists — used by {@code PageContentLookup} (Phase 4, design.md D3). */
    public Optional<String> findExtractedText(String url) {
        return findByUrl(url).map(PageEntity::extractedText);
    }

    /** The page's AI-assigned category (Fase 3), if one was generated — used by {@code intelligence}'s CATEGORY alert rules. */
    public Optional<String> findCategory(String url) {
        try {
            String category = jdbc.queryForObject(
                "SELECT category->>'category' FROM pages WHERE url = ?", String.class, url);
            return Optional.ofNullable(category);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** The archived text of the version right before {@code beforeVersion}, if one was archived. */
    public Optional<String> findPreviousVersionExtractedText(String url, int beforeVersion) {
        try {
            String text = jdbc.queryForObject("""
                SELECT pv.extracted_text
                FROM page_versions pv JOIN pages p ON p.id = pv.page_id
                WHERE p.url = ? AND pv.version = ?
                """, String.class, url, beforeVersion - 1);
            return Optional.ofNullable(text);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
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

    private Long insertNew(Document doc, String hash) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // Postgres' JDBC driver returns the whole row for RETURN_GENERATED_KEYS unless told
            // which column(s) to return — pages has several (search_vector included), so without
            // naming "id" explicitly, KeyHolder.getKey() sees multiple keys and throws.
            PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO pages (url, source_type, raw_html, extracted_text, content_hash, version)
                VALUES (?, ?, ?, ?, ?, 1)
                """, new String[]{"id"});
            ps.setString(1, doc.url());
            ps.setString(2, doc.sourceType());
            ps.setString(3, doc.rawHtml());
            ps.setString(4, doc.extractedText());
            ps.setString(5, hash);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private void archiveVersion(PageEntity current) {
        jdbc.update("""
            INSERT INTO page_versions (page_id, version, content_hash, extracted_text)
            VALUES (?, ?, ?, ?)
            """, current.id(), current.version(), current.contentHash(), current.extractedText());
    }

    private void updateWithNewVersion(Document doc, String hash, int newVersion) {
        jdbc.update("""
            UPDATE pages
            SET extracted_text = ?, content_hash = ?, version = ?,
                raw_html = ?, last_seen_at = now()
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
