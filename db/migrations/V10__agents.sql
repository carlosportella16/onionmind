-- Phase 5: Agent Playground (phase5-agent-playground D4). An agent is configuration only;
-- a run is one report. Single user: no owner column.
-- Rollback: DROP TABLE agent_runs; DROP TABLE agents; (nothing else references them).

CREATE TABLE agents (
    id                 BIGSERIAL PRIMARY KEY,
    name               TEXT NOT NULL UNIQUE,
    instructions       TEXT NOT NULL,
    topic              TEXT NOT NULL,                 -- the search query
    categories         TEXT[] NOT NULL DEFAULT '{}',  -- content.Categories codes; empty = all
    window_days        INT NOT NULL,
    tools              TEXT[] NOT NULL,               -- subset of search, changes, entities
    cost_profile       VARCHAR(20) NOT NULL,          -- 'interactive' | 'batch'
    max_tokens_per_run INT NOT NULL,
    daily              BOOLEAN NOT NULL DEFAULT false,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_runs (
    id                BIGSERIAL PRIMARY KEY,
    agent_id          BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    trigger           VARCHAR(10) NOT NULL,           -- 'manual' | 'daily'
    status            VARCHAR(10) NOT NULL,           -- 'running' | 'succeeded' | 'failed'
    failure_reason    VARCHAR(20),                    -- 'budget' | 'timeout' | 'error'
    report            TEXT,
    sources           JSONB NOT NULL DEFAULT '[]',    -- the cited sources: [{id, tool, url, text}]
    unavailable_tools TEXT[] NOT NULL DEFAULT '{}',   -- enabled but not usable (entities without the graph)
    tokens_used       INT NOT NULL DEFAULT 0,
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ
);

CREATE INDEX idx_agent_runs_agent_started ON agent_runs (agent_id, started_at DESC);
-- At most one scheduled run per agent per (UTC) day, even with two job firings.
CREATE UNIQUE INDEX idx_agent_runs_daily ON agent_runs (agent_id, ((started_at AT TIME ZONE 'UTC')::date))
    WHERE trigger = 'daily';

INSERT INTO agents (name, instructions, topic, categories, window_days, tools, cost_profile, max_tokens_per_run, daily)
VALUES ('Research Agent',
        'Write a short research report on the topic for a researcher who follows it over time: what the '
        || 'indexed pages say about it, what changed recently and which entities are involved. Point out '
        || 'gaps and contradictions between sources.',
        'bitcoin', '{}', 7, '{search,changes,entities}', 'batch', 8000, false);
