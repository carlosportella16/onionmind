-- Phase 4: rule-based alerts reacting to PageIndexedEvent (master-sdd sec. 6.6) — "regras
-- simples, não ML". UNIQUE on alerts guards against duplicate rows if the event is ever
-- redelivered (ADR-009 is at-least-once), same reasoning as page_diffs.

CREATE TABLE alert_rules (
    id             BIGSERIAL PRIMARY KEY,
    criteria_type  TEXT NOT NULL,   -- 'KEYWORD' | 'CATEGORY' | 'ENTITY_TYPE'
    criteria_value TEXT NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE alerts (
    id            BIGSERIAL PRIMARY KEY,
    rule_id       BIGINT NOT NULL REFERENCES alert_rules(id),
    page_id       BIGINT NOT NULL REFERENCES pages(id),
    page_version  INT NOT NULL,
    triggered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rule_id, page_id, page_version)
);
CREATE INDEX idx_alerts_triggered_at ON alerts (triggered_at);
