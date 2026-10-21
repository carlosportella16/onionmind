-- Phase 3: track the AI generation lifecycle per page + quarantine of illegal content.
-- The generated fields (summary, category, language) already exist as JSONB since V1 —
-- this migration only adds status tracking, mirroring V3's embedding_status pattern.

ALTER TABLE pages ADD COLUMN ai_status VARCHAR(20) NOT NULL DEFAULT 'pending';
-- 'pending' | 'processed' | 'unchanged' | 'failed_transient' | 'failed_permanent' | 'quarantined'
ALTER TABLE pages ADD COLUMN ai_processed_at TIMESTAMPTZ;
ALTER TABLE pages ADD COLUMN ai_error_message TEXT;

CREATE INDEX idx_pages_ai_status ON pages (ai_status);

-- Minimal audit trail for pages blocked by IllegalContentGuard. No extracted_text,
-- no raw_html — only enough to prove why it was dropped and allow review.
CREATE TABLE quarantined_pages (
    id            BIGSERIAL PRIMARY KEY,
    url           TEXT NOT NULL,
    content_hash  TEXT NOT NULL,
    reason        TEXT NOT NULL,          -- 'url-denylist' | 'text-pattern:<line>'
    detected_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_quarantined_pages_url ON quarantined_pages (url);
