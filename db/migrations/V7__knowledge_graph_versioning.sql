-- Phase 4: knowledge graph + "what changed" (phase4-knowledge-graph-versioning).

-- Defensive: nothing ever enforced one row per (page_id, version). Keep the earliest copy.
DELETE FROM page_versions pv
USING page_versions dup
WHERE pv.page_id = dup.page_id AND pv.version = dup.version AND pv.id > dup.id;

CREATE UNIQUE INDEX idx_page_versions_page_version ON page_versions (page_id, version);

-- Enrichment the archived version had — copied only when it really belonged to that
-- version (pages.ai_status = 'processed' at archive time), so the diff never compares
-- against a stale snapshot (design D7).
ALTER TABLE page_versions ADD COLUMN entities JSONB;
ALTER TABLE page_versions ADD COLUMN category JSONB;
ALTER TABLE page_versions ADD COLUMN summary  JSONB;

CREATE TABLE page_changes (
    id               BIGSERIAL PRIMARY KEY,
    page_id          BIGINT NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
    url              TEXT NOT NULL,
    from_version     INT NOT NULL,
    to_version       INT NOT NULL,
    added_text       TEXT,
    removed_text     TEXT,
    entities_added   JSONB NOT NULL DEFAULT '[]',
    entities_removed JSONB NOT NULL DEFAULT '[]',
    category_from    TEXT,
    category_to      TEXT,
    status           VARCHAR(20) NOT NULL,
    -- 'pending' | 'trivial' | 'summarized' | 'failed_transient' | 'failed_permanent'
    attempts         INT NOT NULL DEFAULT 0,
    summary          JSONB,
    detected_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_page_changes_page_to_version ON page_changes (page_id, to_version);
CREATE INDEX idx_page_changes_detected_at ON page_changes (detected_at);
CREATE INDEX idx_page_changes_category_detected_at ON page_changes (category_to, detected_at);
CREATE INDEX idx_page_changes_status ON page_changes (status);
