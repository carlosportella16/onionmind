-- Phase 2: semantic search support (embedding lifecycle tracking).
-- Vector payloads live in Qdrant, keyed by url (SDD Fase 2, sec. 3.5) — PostgreSQL only
-- tracks page-level status, feeding the backfill query described in sec. 3.6.

ALTER TABLE pages ADD COLUMN embedding_status VARCHAR(20) NOT NULL DEFAULT 'pending';
-- Values: 'pending', 'embedded', 'unchanged', 'failed_transient', 'failed_permanent'

ALTER TABLE pages ADD COLUMN embedding_attempted_at TIMESTAMPTZ;
ALTER TABLE pages ADD COLUMN embedding_error_message TEXT;

CREATE INDEX idx_pages_embedding_status ON pages (embedding_status);
