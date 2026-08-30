-- Phase 3: the Portuguese translation the order 20 processor produces for non-PT pages.
-- Stored separately so extracted_text keeps the original (search and re-embedding stay on
-- the source content, fase3-sdd 7.6). Shape: {"text": "...", "detectedLanguage": "en", "confidence": 0.9}
ALTER TABLE pages ADD COLUMN translated_text JSONB;
