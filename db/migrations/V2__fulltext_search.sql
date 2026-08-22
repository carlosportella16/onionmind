ALTER TABLE pages
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(extracted_text, ''))) STORED;

CREATE INDEX idx_pages_search_vector ON pages USING GIN (search_vector);
