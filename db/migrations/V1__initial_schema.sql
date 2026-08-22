CREATE TABLE pages (
    id              BIGSERIAL PRIMARY KEY,
    url             TEXT NOT NULL,
    source_type     TEXT NOT NULL DEFAULT 'tor',

    raw_html        TEXT,
    extracted_text  TEXT,

    content_hash    TEXT NOT NULL,
    version         INT NOT NULL DEFAULT 1,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    summary         JSONB,
    category        JSONB,
    language        JSONB,
    entities        JSONB,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_pages_url ON pages (url);
CREATE INDEX idx_pages_content_hash ON pages (content_hash);
CREATE INDEX idx_pages_source_type ON pages (source_type);

CREATE TABLE page_versions (
    id              BIGSERIAL PRIMARY KEY,
    page_id         BIGINT NOT NULL REFERENCES pages(id),
    version         INT NOT NULL,
    content_hash    TEXT NOT NULL,
    extracted_text  TEXT,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
