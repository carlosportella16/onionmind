-- Phase 4: natural-language summary of what changed between two versions of a page
-- (master-sdd sec. 5/8.2). One row per version transition, not per page — a page accumulates
-- one row every time it changes. UNIQUE guards against duplicate rows if PageIndexedEvent is
-- ever redelivered (ADR-009 is at-least-once).

CREATE TABLE page_diffs (
    id            BIGSERIAL PRIMARY KEY,
    page_id       BIGINT NOT NULL REFERENCES pages(id),
    from_version  INT NOT NULL,
    to_version    INT NOT NULL,
    summary       JSONB NOT NULL,      -- {"text": "...", "confidence": 0.9}
    generated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (page_id, from_version, to_version)
);
