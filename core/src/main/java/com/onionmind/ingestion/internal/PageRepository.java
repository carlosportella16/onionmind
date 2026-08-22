package com.onionmind.ingestion.internal;

import com.google.common.hash.Hashing;
import com.onionmind.content.Document;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Repository
public class PageRepository {
    private final JdbcTemplate jdbc;

    public PageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertWithVersioning(Document doc) {
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
