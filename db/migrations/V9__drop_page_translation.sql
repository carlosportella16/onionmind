-- remove-page-translation: enrichment no longer translates pages (design D3). The column
-- from V5 would stay always null; the translations it held only ever existed in dev.
-- Rollback: ALTER TABLE pages ADD COLUMN translated_text JSONB; (empty).
ALTER TABLE pages DROP COLUMN translated_text;
