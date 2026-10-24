-- Failed AI enrichment runs per page (fix-real-infra-findings D2). AiEnrichmentBackfillJob
-- moves a page to 'failed_permanent' once this reaches ai.backfill.max-attempts; a new
-- version resets it to 0. Same shape as page_changes.attempts (V7).
ALTER TABLE pages ADD COLUMN ai_attempts INT NOT NULL DEFAULT 0;
