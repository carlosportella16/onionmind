# Agents (playground)

OpenSpec change `phase5-agent-playground`, branch `feat/phase5-agent-playground`. Second item of the Phase 5 gate: "Agent Playground funcional".

## Agent model

An agent is configuration over what already runs, with no code per agent (`mvp-architecture.md`, Phase 5). Stored in Postgres (`agents`, migration V10) and managed through `/api/agents`:

| Field | Meaning | Limit |
|---|---|---|
| `name` | unique | 100 chars |
| `instructions` | free text sent to the LLM with the topic | `agents.max-instructions-chars` (4000) |
| `topic` | the search query | 200 chars |
| `categories` | `content.Categories` codes that filter the changes; empty = all | taxonomy only |
| `windowDays` | how far back the changes go | 1 to 30 (same ceiling as `/api/changes`) |
| `tools` | subset of `search`, `changes`, `entities` | at least one |
| `costProfile` | `interactive` or `batch` | |
| `maxTokensPerRun` | token ceiling of one run | 1 to 100000 |
| `daily` | run once a day by the scheduler | |

A rejected configuration answers `{"errors": {field: message}}`: 400 when invalid (an unknown tool is named in the message), 409 when the name is taken. Deleting an agent deletes its runs (`ON DELETE CASCADE`).

The migration seeds a **Research Agent**: the three tools, `batch`, `daily = false`, topic `bitcoin`, 7-day window, 8000-token ceiling.

## Interpretation of "subset of processors" (design D1)

The SDDs define an agent as "a named composition of a subset of processors". The `ContentProcessor`s run at ingestion, per page, and make no sense as a step of an agent that queries the base. The agent's fixed set is therefore **query tools**:

| Tool | Source ids | What it collects |
|---|---|---|
| `search` | `S1`… | passages for the topic from the chat's `PassageRetriever` (hybrid full-text + vector, RRF), in RRF order |
| `changes` | `C1`… | summarized changes in the window and categories, newest first, up to 20 (`intelligence.ChangeQuery`) |
| `entities` | `E1`… | the up to 5 entities most mentioned by the pages found for the topic, each with the pages citing it and its co-occurrences (`GraphRepository.mostMentionedIn` + `find`) |

Collection is deterministic; the LLM only writes the report. There is no agentic loop: the cost stays predictable and the behavior testable. The search runs once per run and is shared by `search` and `entities`; an agent with `changes` alone does not search. Categories filter only the changes, as D1 says.

## Run

`POST /api/agents/{id}/runs` runs synchronously and returns the run; `GET /api/agents/{id}/runs` lists runs newest first and `GET /api/agents/{id}/runs/{runId}` returns one.

1. The run is stored as `running`.
2. The enabled tools collect. `entities` without the graph (`graph.enabled=false`) is skipped and recorded in `unavailableTools`; the run goes on.
3. Nothing collected → `succeeded` with "The indexed pages have no material on this topic in the period.", no LLM call.
4. **Ceiling (D3).** Estimate at chars/4. The budget for sources is `maxTokensPerRun − 1000 − instructions − topic`; the 1000 reserved tokens cover the system prompt and the report itself. Sources go in priority order (search by RRF, changes newest first, entities) and one that does not fit is left out. Not one fits → `failed` with reason `budget`, no LLM call.
5. `AIOrchestrator.report` (`TaskType.REPORT`, prompt opening with "Write a research report") writes an English report under 500 words that cites the source ids. Sources go in `<source id="…">` tags as data; ids that were not sent are dropped, and a report with no valid citation becomes the no-material sentence. `interactive` profile → interactive task (only the safety margin of the budget); `batch` → batch margin (`ai-token-quota`).
6. The run is stored `succeeded` with the report, the **cited** sources and `tokensUsed` (the chars/4 estimate of what was sent plus the report).

Deadline (D5): `agents.interactive-timeout` (20 s) or `agents.batch-timeout` (60 s), over collection and generation. Past it the run is `failed/timeout`; a provider failure is `failed/error`.

**Daily runs.** `DailyAgentJob` (`agents.daily.cron`, default 06:00 UTC, only with `ai.enabled`) runs every agent with `daily = true`, always as batch. A partial unique index on `(agent_id, UTC date of started_at) WHERE trigger = 'daily'` makes a second firing the same day a no-op, even from a second instance.

## Single user

Decision of the user (2026-10-24): one user, no identity, no login, no sharing. "Private per user" holds because there is only one user. The instructions are free text that reaches the LLM: acceptable for one trusted user; page content stays delimited as data. Runs are synchronous and hold the request up to 60 s, fine for one user; the playground shows a loading state.

## Deviations from the design

- `agent_runs.unavailable_tools` (not in D4) records a tool that was enabled but unusable.
- The `agents` module depends on `search` (`PassageRetriever`) and `ai` (`report`), not on `chat`: the chat's service is internal and those two are what it uses too.
- The daily index uses the UTC date, since `date(timestamptz)` is not immutable and cannot be indexed.
- The ceiling reserves 1000 tokens for the system prompt and the report, so the minimal prompt is instructions + topic + one source + 1000.

## Backlog

- **Multi-user:** identity, agents per user, quotas per user.
- **Public sharing of agents/reports:** only with moderation (`sdd.md` section 9).
- **Agentic loop:** an agent that plans, picks tools and iterates; only if the deterministic report proves insufficient.
- User-defined tools; asynchronous runs with polling if reports get long or users multiply.
- Real token usage per run instead of the chars/4 estimate.

## Real validation (task 4.4)

**Pending.** 2026-10-25: not run. The Gemini key is still on the free tier: probes by the parallel validation (`replace-ollama-with-gemini-deepseek` 5.3) got HTTP 429 with `quotaId: EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier`. Page text may only go to Gemini paid tier and DeepSeek, so no core ran with real keys and no corpus text was sent. To do once billing is on: run the Research Agent on a topic of the real corpus (after `crawler-follow-onion-listings`), check the citations by hand and that `tokensUsed` stays within `maxTokensPerRun`, and record the result here.
