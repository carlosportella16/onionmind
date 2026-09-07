-- Postgres's text-search parser has a hard position-count ceiling (MAXSTRPOS, 1,048,575)
-- roughly tied to input length — a page with ~2MB of extracted_text overflows it and fails
-- the whole INSERT/UPDATE, not just the generated column (fix-large-page-backfill-limits,
-- found live 2026-09-07). Cap what search_vector indexes, not what extracted_text stores:
-- 500,000 is comfortably under half the observed failure threshold. Postgres can't alter a
-- generated column's expression in place, so this drops and re-adds it — recomputed for
-- every existing row from the already-stored extracted_text, no data loss.
DROP INDEX idx_pages_search_vector;
ALTER TABLE pages DROP COLUMN search_vector;

ALTER TABLE pages
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', left(coalesce(extracted_text, ''), 500000))) STORED;

CREATE INDEX idx_pages_search_vector ON pages USING GIN (search_vector);
