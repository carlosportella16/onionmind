# OnionMind

> AI-powered Knowledge Discovery Platform for the Tor Network.

**Current Phase:** Phase 3 validated end-to-end (2026-09-06) — real generation pipeline (summarize / classify / detect language) with a multi-provider `AIOrchestrator` (Ollama, Groq, Gemini), an AI Cost Optimizer, an illegal-content guard, and enrichment surfaced in search, all proven against live Groq + Gemini + Ollama (see `docs/onionmind-fase3-sdd.md` §11.4 / §12). Phase 2's exit gate has also been re-run against live Ollama + Qdrant and its `min-score` recalibrated from real corpus data (§P2-1/P2-2).

**Tech Stack:** Go (crawler) • Java 25 (Spring Boot 4.1 + Spring Modulith) • PostgreSQL • Redpanda • React

---

## 🎯 Overview

OnionMind discovers, crawls, indexes, and analyzes `.onion` content, transforming unstructured pages into searchable knowledge via AI.

### Why?

Most Tor search engines are link lists without ranking or context. OnionMind changes that:
- **Continuous Discovery:** Go crawler autonomously finds new pages via Tor
- **Intelligent Indexing:** Full-text search (PostgreSQL), semantic search (Qdrant), knowledge graph (Neo4j)
- **Free AI:** Summaries, classification, entity extraction — locally via Ollama + free tiers (Groq, Gemini Flash)
- **Incremental Delivery:** Each phase ships a working product — no phase exists just for infrastructure

---

## 🏗️ Architecture

```
┌────────────────────────────────────────────────────────┐
│                   React SPA (web/)                      │
│              GET /api/search?q=cryptocurrency          │
└─────────────────────────┬────────────────────────────┘
                          │
              ┌───────────▼──────────┐
              │  Spring Boot 4.1     │
              │  (Modular Monolith)  │
              │                      │
              │  ┌────────────────┐  │
              │  │ search module  │  │  ← Full-text + semantic
              │  ├────────────────┤  │
              │  │ ingestion      │  │  ← Kafka consumer
              │  ├────────────────┤  │
              │  │ content        │  │  ← Plugin pipeline
              │  ├────────────────┤  │
              │  │ ai module      │  │  ← LLM orchestration
              │  └────────────────┘  │
              └─────────────────────┘
                   ▲       │
                   │       ▼
        ┌──────────┴──────┬──────────────┐
        │                 │              │
    PostgreSQL        Redpanda        Redis
  (full-text +      (raw pages)      (dedup)
   embeddings)

    Phase 2+: Qdrant (embeddings) + Neo4j (graph)
```

---

## 📊 Phases

| Phase | Scope | Status | Completion Gate |
|-------|-------|--------|---|
| **0** | Repository, CI, Docker Compose, initial schema | ✅ Complete | Infrastructure boots, tests pass |
| **1** | Crawler + full-text search (real MVP) | ✅ Complete | Discover → crawl → index → search in minutes |
| **2** | Semantic search (embeddings + Qdrant) | ✅ Complete — live gate re-run 2026-09-06 | Concept-based search works |
| **3** | AI generation (summarize, classify) | ✅ Complete — validated live 2026-09-06 | New page summarized in minutes, quota never exceeded |
| **4** | Knowledge graph + versioning | 🚧 Implemented — live gate pending | "What changed?" answers correctly |
| **5** | Full RAG + Agent Playground | ⏳ Planned | RAG pipeline functional, real-world tested |
| **6** | External connectors (RSS, GitHub, PDF) | ⏳ Backlog | — |

### Phase 1 Details

Ships a functional Tor search engine without AI:
- **Crawler:** TorConnector discovers URLs via Tor (anchors and plain-text v3 `.onion` addresses, capped per page), never fetches hosts on its MD5 denylist (see [Crawler host denylist](#crawler-host-denylist)), avoids traps, deduplicates with Redis. A page too large to publish is recorded (not just logged) at `skipped:oversized:<sha256(url)>` — `redis-cli --scan --pattern 'skipped:oversized:*'` lists them, each value is `url|size_bytes|timestamp`
- **Sanitization:** HTML cleaned before any persistence (OWASP allowlist)
- **Indexing:** PostgreSQL `tsvector` GENERATED column + GIN index, `ts_rank` for relevance
- **REST API:** GET `/api/search?q=term` returns ranked pages
- **Frontend:** Minimal React SPA with search input

### Phase 3 Details

Adds LLM-generated understanding without coupling to one provider or a paid account:
- **`AIOrchestrator`:** one interface (`summarize` / `classify` / `translate` / `detectLanguage` / `embed`); a decorator chain (metrics → cache → retry → validation) with a provider-escalation loop that switches provider on a low-confidence answer and returns the best result rather than throwing.
- **Three adapters:** `OllamaProvider` (local, no rate limit), `GroqProvider` (fast, daily + per-minute cap), `GeminiProvider` (1M context, tighter cap). Missing API key → the provider reports zero quota and is skipped.
- **AI Cost Optimizer:** short non-critical text starts local, long/critical starts on the cloud; Ollama is always the floor. `ProviderQuotaTracker` counts daily and per-minute usage in Redis.
- **Enrichment processors** (`ContentProcessor`, order 10–40): language detection (local stopword heuristic, escalates to the LLM when ambiguous), 2–3 sentence summary of the original text, fixed-taxonomy classification. Each skips work when `content_hash` is unchanged. Pages are not translated (removed in `remove-page-translation`: no API read it, and it was the largest call); `translate` stays on `AIOrchestrator` for the bilingual-search backlog.
- **English output:** every prompt is in English and every LLM-generated text — page summary, change summary — comes out in English, whatever the page language. Category and entity-type codes are fixed identifiers. Summaries written in Portuguese before this change stay until the page changes; to standardize the corpus, re-queue it in batches, e.g. `UPDATE pages SET ai_status = 'pending' WHERE id IN (SELECT id FROM pages WHERE ai_status = 'processed' AND language->>'code' = 'pt' ORDER BY id LIMIT 200);` — each page spends LLM quota again.
- **Illegal-content guard** (order 5, before any model): URL/host denylist + audited text patterns; a match halts the pipeline, records an audit row and persists no content. Perceptual image hashing is a documented gap (no images are stored).
- **Search:** `/api/search` results carry `summary` and `category` read straight from JSONB — no LLM on the query path.
- **Backfill:** a scheduled job re-runs the enrichment processors for pages stuck at `pending` / `failed_transient`.
- **Limits:** each LLM call gets at most `ai.max-input-chars` (default 8000) characters of page text (`entities.max-input-chars` for named entities), so 1–2 MB pages stop hitting provider length limits. The summary of a page past that cap covers only its beginning; `extracted_text`, full-text search and the entity patterns still see the whole page. A page whose enrichment fails `ai.backfill.max-attempts` times (default 5, counted in `pages.ai_attempts`) goes `failed_permanent` and leaves the backfill queue and `ingestion.ai.lag`. A re-crawl with new content resets it to `pending` with zero attempts; to re-queue by hand: `UPDATE pages SET ai_status = 'pending', ai_attempts = 0 WHERE ai_status = 'failed_permanent' AND ...;`.

Enable it with `ai.enabled=true` and the two API keys (below). With it off, the app behaves exactly as Phase 2.

### Phase 4 Details

Relates entities across pages and answers "what changed" (`docs/onionmind-fase4-sdd.md`):
- **Entities** (`EntityProcessor`, order 50): wallets (legacy/bech32 BTC checksum-validated, ETH, XMR), v3 `.onion` addresses (checksum-validated), e-mails and PGP fingerprints come from patterns — no LLM. People, organizations, places and technologies come from `AIOrchestrator.extractEntities`, through the same router, quota and validation as Phase 3. Stored in `pages.entities` with a normalized key and confidence.
- **Events (ADR-009):** a complete AI enrichment publishes `PageEnrichedEvent`; quarantine publishes `PageQuarantinedEvent`. The `graph` and `intelligence` modules react through `@ApplicationModuleListener` — async, after commit, durable in the Event Publication Registry and redelivered by `EventResubmissionJob`.
- **Knowledge graph** (Neo4j, `graph.enabled`): `(:Page)-[:MENTIONS]->(:Entity)` plus `(:Page)-[:HOSTED_ON]->(:OnionService)`. A projection of `pages.entities` — a new version replaces the page's mentions, quarantine removes the page. `GET /api/entities/{type}/{key}` returns the pages citing an entity and what co-occurs with it.
- **What changed** (`intelligence`): each new enriched version is diffed against the last enriched one (sentence sets, digits masked, so counters and dates are not changes), with entity and category deltas. Real changes are summarized by a scheduled job; `GET /api/changes?since=&category=&limit=` serves them with no live LLM call. Quarantine also erases archived text and change history.

---

## 🚀 Getting Started

### Requirements

- **Java 25** (via Gradle toolchain — no manual install needed)
- **Go 1.27**
- **Node 22+** (frontend)
- **Docker + Docker Compose** (PostgreSQL, Redpanda, Redis, Tor proxy)
- **Make** (optional, crawler convenience)

### Local Setup

```bash
# Clone
git clone https://github.com/carlosportella/onionmind.git
cd onionmind

# Start infrastructure — postgres, redpanda, redpanda-console, redis, tor,
# ollama (pulls nomic-embed-text for embeddings + llama3.1:8b for generation),
# qdrant (Phase 2+), neo4j (Phase 4). The tor image is built from tor/Dockerfile
# (Alpine-packaged Tor); after pulling a change to it, run `docker compose build tor`.
docker-compose up -d

# Wait for services to report healthy — the two ollama-pull jobs run once and exit.
# tor turns healthy only once its log reaches "Bootstrapped 100%"; start the crawler after that.
docker compose ps

# Build and test Java core
cd core
./gradlew clean test jacocoTestReport

# Build Go crawler
cd ../crawler
make build

# Install frontend deps
cd ../web
npm install
```

### Running

```bash
# Terminal 1: Spring Boot API (talks to postgres/redpanda on localhost)
cd core
./gradlew bootRun --args='--spring.profiles.active=sandbox'
# API available: http://localhost:8081
```

**Enabling AI (Phase 2 embeddings and/or Phase 3 generation):** both are off by
default. Set the flags and, for generation, the free-tier API keys as
environment variables (never commit them):

```bash
export GROQ_API_KEY=...      # https://console.groq.com
export GEMINI_API_KEY=...    # https://aistudio.google.com
./gradlew bootRun --args='--spring.profiles.active=sandbox \
  --embedding.enabled=true --ai.enabled=true'
```

**Enabling the knowledge graph (Phase 4):** entity extraction and change detection
come with `ai.enabled=true`; the Neo4j projection needs the `neo4j` compose service
and `graph.enabled=true`:

```bash
docker compose up -d neo4j      # browser at http://localhost:7474, neo4j / onionmind_dev_only
./gradlew bootRun --args='--spring.profiles.active=sandbox --ai.enabled=true --graph.enabled=true'

curl 'http://localhost:8081/api/changes?category=forum'                        # last 24h by default
curl 'http://localhost:8081/api/entities/crypto_wallet/1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa'
```

- **Rebuild the graph** from Postgres (also how pages enriched before Phase 4 get in):
  start once with `--graph.rebuild=true` — it wipes Neo4j and reprojects every
  `processed` page from `pages.entities`.
- **Entities for pages enriched before Phase 4:** re-queue them for the AI backfill in
  batches, e.g. `UPDATE pages SET ai_status = 'pending' WHERE id IN (SELECT id FROM pages
  WHERE ai_status = 'processed' AND entities IS NULL ORDER BY id LIMIT 200);`. Each page
  spends LLM quota again (all enrichment steps re-run), so do it deliberately.

With no key set for a provider it simply reports zero quota and the router
skips it, degrading to local Ollama. Limits in `application.yml` are the
free-tier numbers as of Aug 2026 — reconfirm before production.

**Note:** `docker-compose.yml`'s `redpanda` service only advertises its
in-network hostname (`redpanda:9092`) by default for containers on the
compose network. A Java process running on the host (like `bootRun` above)
needs the external listener instead — pass
`--spring.kafka.bootstrap-servers=localhost:19092` if the consumer keeps
retrying with `UnknownHostException: redpanda`.

```bash
# Terminal 2: Crawler — build the image once, then run it on the compose network
cd crawler
docker build -t onionmind-crawler .
docker run --rm --network onionmind_default onionmind-crawler

# Terminal 3: Frontend dev server (proxies /api to localhost:8081)
cd web
npm run dev
# UI available: http://localhost:5173
```

#### Crawler host denylist

The frontier follows `<a href>` links **and** v3 `.onion` addresses written as plain text (ahmia's `/onions/` listing has no anchors at all). Before a URL is queued, and again before it is fetched (seeds included), the crawler checks its host against a list of banned-host MD5 hashes and drops it on a match. This is the first line of defence; the core's illegal-content guard still filters after ingestion.

Two settings in `crawler/config.yaml`, under `crawler`:

| Key | Default | Meaning |
|-----|---------|---------|
| `max_links_per_page` | `2000` | At most this many distinct `.onion` links are queued per page, so a listing with thousands of hosts cannot take over the queue. |
| `host_denylist_path` | `denylist/banned-hosts.md5` | File of banned-host hashes, relative to the config file. **Required**: a missing file stops the crawler at boot. An empty file is allowed (the log says `hosts=0`). |

The file has ahmia's `/banned/` format: one hex MD5 per line, each the hash of the bare domain `<56 base32>.onion`. No scheme, port, path or subdomain, and lowercase, as in ahmia-site's `BannedDomainListView`. The crawler normalizes every URL the same way before hashing, so case, `https://`, ports, paths and `www.` do not matter. Blank lines are skipped, and any other line that is not a 32-char hex hash is dropped and counted in the log (`invalid_lines`).

The repo ships only an empty placeholder (`crawler/denylist/banned-hosts.md5`, baked into the image). As with the core's URL denylist, the populated list stays **out of the repo** (never write it into `crawler/denylist/`). Generate it at deploy, review it, and mount it over the placeholder:

```bash
# ahmia's /banned/ already serves the hashes; keep only well-formed lines.
curl -s https://ahmia.fi/banned/ | grep -Eo '^[0-9a-f]{32}$' > /secure/banned-hosts.md5
# To add a host of your own (Linux md5sum; on macOS use `md5 -q -s '<host>.onion'`):
printf '%s' '<56 base32>.onion' | md5sum | cut -c1-32 >> /secure/banned-hosts.md5

docker run --rm --network onionmind_default \
  -v /secure/banned-hosts.md5:/denylist/banned-hosts.md5:ro onionmind-crawler
```

**Search directly against the API:** http://localhost:8081/api/search?q=bitcoin

With `ai.enabled=true`, each result also carries the generated `summary` and
`category`:

```json
{
  "url": "http://example.onion/",
  "snippet": "...highlighted match...",
  "summary": "Fórum sobre troca anônima de criptomoedas e ferramentas de privacidade.",
  "category": "forum",
  "rank": 0.53, "version": 1
}
```

---

## 🧪 Testing & Quality

### Coverage Requirements

- **Java:** 80% minimum (JaCoCo)
- **Go:** 80% minimum (go test -cover)
- **Enforcement:** CI fails if coverage drops below threshold

### Run Tests Locally

```bash
# Java: tests + coverage report
cd core
./gradlew clean test jacocoTestReport

# Go: tests + coverage percentage
cd crawler
make test-coverage
```

### Acceptance (Cucumber) and Smoke Tests

**Acceptance suite** — `core/src/test/resources/features/fase{1,2,3,4}/*.feature`, Gherkin in Portuguese, one folder per phase:

- Fase 1 covers ingestion, versioning, sanitization, stats and full-text search.
- Fase 2 covers concept search, the `min-score` floor, one page not crowding out the others, and hybrid fusion.
- Fase 3 covers enrichment (English summary, no translation), the taxonomy, quarantine and quota fallback.
- Fase 4 covers entities in the graph (shared across pages, bad checksums ignored), "what changed" by category, trivial changes hidden, and quarantine leaving both the graph and the history.

It runs inside the normal `./gradlew test` through `AcceptanceSuite`, so it is part of CI and of the coverage gate.

What is real vs. fake:

| Real (Testcontainers / in-process) | Fake |
|---|---|
| Postgres, Kafka (`raw-pages`), Redis (quota + cache), Qdrant, Neo4j, pipeline, event listeners, `CostOptimizerRouter`, `ProviderQuotaTracker`, decorators, Ollama/Groq/Gemini HTTP adapters | The model endpoints only: three local HTTP stubs answer generation deterministically and return bag-of-concepts embeddings (`FakeAiProviders`) |

No API keys, Tor or local models are needed.

```bash
cd core
./gradlew test --tests '*AcceptanceSuite*'   # only the Cucumber scenarios
```

**Smoke tests** — `features/smoke/smoke.feature`, tagged `@smoke`.

- They send GET requests only against an already running instance and write nothing, so they are safe against shared environments.
- They are excluded from `./gradlew test`. In CI, the `smoke` job (after `ci-java`) boots the core against real Postgres, Kafka and Redis and runs them with `SMOKE_TAGS='@smoke and not @semantic and not @grafo'` (there is no Ollama, Qdrant or Neo4j on the runner).

```bash
./scripts/smoke.sh                                         # http://localhost:8081, every @smoke scenario
SMOKE_BASE_URL=https://host ./scripts/smoke.sh             # another instance
SMOKE_TAGS='@smoke and not @semantic' ./scripts/smoke.sh   # instance with embedding.enabled=false
SMOKE_TAGS='@smoke and not @ia' ./scripts/smoke.sh         # instance with ai.enabled=false
```

Tags:

- `@semantic` — `/api/search/semantic` (503 when embeddings are off).
- `@ia` — AI metrics in `/actuator/prometheus`.
- `@mudancas` — `/api/changes` contract and parameter validation.
- `@grafo` — `/api/entities` 404/400 contract (the endpoint only exists with `graph.enabled=true`).
- `@somente-leitura` — checks that `/api/stats` is unchanged by the run. Exclude it if a crawler is ingesting into the same instance at the same time.

### Crawler Acceptance Tests (godog)

`crawler/features/*.feature` are Gherkin scenarios in Portuguese, run by [godog](https://github.com/cucumber/godog) through `go test`. They are behind the `acceptance` build tag, so a plain `go test ./...` still needs no Docker.

The scenarios drive the real wiring (`internal/app.Run`) against real Redis and Redpanda containers (testcontainers-go). The Tor network is replaced by `internal/testfakes`: a SOCKS5 proxy that resolves `.onion` names itself, as Tor does with socks5h, plus the fake `.onion` sites behind it.

They cover:

- publishing in the core's JSON contract;
- following links up to `max_depth` while ignoring clearnet links;
- dedup by normalized URL;
- failing open when Redis is down;
- recording oversized pages;
- isolating hosts that fail;
- shutting down cleanly.

```bash
cd crawler
go test -tags acceptance -run TestFeatures -v .    # only the scenarios (~25 s)
go test -tags acceptance -coverpkg=./... ./...     # what CI runs
```

### End-to-End Smoke (all phases)

```
crawler image ──SOCKS5──▶ e2e-fakes (fake Tor + .onion sites)
      │
      ▼ raw-pages
  Redpanda ──▶ core ──▶ Postgres (Fase 1)
                 ├────▶ Qdrant + fake Ollama embeddings (Fase 2)
                 ├────▶ fake Ollama generation, denylist quarantine (Fase 3)
                 └────▶ entities → Neo4j (Fase 4)
                 ▲
     @e2e + @smoke Cucumber scenarios (GET only)
```

```bash
./scripts/e2e-smoke.sh   # needs Docker and a free port 8081; ~30 s with warm caches
```

The script runs these steps:

1. Builds the core jar.
2. Starts `e2e/docker-compose.e2e.yml`, which includes the **real crawler image** and `crawler/cmd/e2e-fakes` (Tor, the `.onion` sites and Ollama).
3. Runs the `@e2e` scenarios. They check that the seed and the linked page are searchable, that a page reached only through a plain-text `.onion` address is indexed, that semantic search finds a page by concept, that results carry a summary and category, that the denylisted host is not indexed, and that a crawled wallet shows up in `/api/entities` with its page.
4. Checks that the crawler container exits with code 0 on `SIGTERM`.
5. Runs every `@smoke` scenario, `@semantic` and `@ia` included.

The stack is always torn down at the end. On failure, the script prints the crawler, core and fakes logs first.

Nothing reaches Tor or an AI provider. Real-provider validation stays in `scripts/validate-phase3.sh`.

In CI, `ci-go.yml` runs the godog suite (with Redis as a service, so the `dedup` unit tests no longer skip), and `e2e.yml` runs `scripts/e2e-smoke.sh` on every PR.

### Code Quality

- **SonarCloud:** Free tier analysis integrated in CI
- **Modulith Fitness:** Spring Modulith boundary violations fail the build
- **Checkstyle:** Java code style enforcement

**Dashboard:** https://sonarcloud.io/dashboard?id=onionmind-java

---

## 🔄 CI/CD Pipeline

### Workflow

```
feature/* branch
    ↓
GitHub PR (automated checks)
    ├─ CI Java: test + coverage ≥80% + Modulith verify + Sonar ✓
    ├─ CI Go: vet + test + coverage ≥80% ✓
    └─ Status: all checks pass ✓
    ↓
[REQUIRE 1 CODEOWNERS APPROVAL]
    (@carlosportella or @fepersilva)
    ↓
Merge to main
```

### Quality Gates (Blocking)

1. **Coverage 80%** — Missing tests block merge
2. **Sonar A-rating** — Code smells/security issues block merge
3. **Modulith Fitness** — Module boundary violations block merge
4. **Code Owner Approval** — 1 of 2 approvers required

### CI Configuration

- **Branch Protection:** `main` requires 1 approval + all status checks
- **Stale Reviews:** Dismissed on new commits (reviewers re-approve)
- **Status Checks:** ci-java, ci-go, sonarcloud required

---

## 📁 Repository Structure

```
onionmind/
├── README.md                          # This file
├── docker-compose.yml                 # Local infrastructure
├── .github/
│   ├── workflows/
│   │   ├── ci-java.yml               # Build + test + coverage + Sonar
│   │   └── ci-go.yml                 # Go vet + test + coverage
│   └── CODEOWNERS                    # @carlosportella @fepersilva
├── docs/
│   ├── onionmind-master-sdd.md      # Master SDD (consolidated design)
│   ├── onionmind-fase1-sdd.md       # Phase 1 detailed specification
│   └── onionmind-mvp-architecture.md
├── db/
│   └── migrations/
│       ├── V1__initial_schema.sql   # Base schema (Phase 0)
│       └── V2__fulltext_search.sql  # Full-text indexes (Phase 1)
├── core/                             # Java — Spring Boot 4.1 + Modulith
│   ├── build.gradle                 # Gradle + JaCoCo + Sonar config
│   └── src/
│       ├── main/java/com/onionmind/
│       │   ├── ingestion/          # Module: Kafka consumer + pipeline
│       │   │   └── internal/       # PageEntity/PageRepository (versioned persistence)
│       │   ├── content/            # Module: ContentProcessor plugins (HtmlSanitizerProcessor)
│       │   ├── search/             # Module: Search REST API (own SQL, no ingestion dep)
│       │   └── ai/                 # Module: LLM orchestration (stubs Phase 0)
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── application-sandbox.yml
│       │   └── application-prod.yml
│       └── test/java/
├── crawler/                          # Go — TorConnector, worker pool, dedup, frontier
│   ├── Makefile                     # make test, make build, make clean
│   ├── Dockerfile                   # distroless, non-root
│   ├── config.yaml                  # workers, seeds, Tor/Redis/Redpanda addresses
│   ├── go.mod, go.sum
│   ├── denylist/banned-hosts.md5    # empty placeholder for the banned-host MD5 list
│   ├── cmd/crawler/main.go
│   └── internal/{config,connector,dedup,denylist,frontier,normalizer,publisher,worker}/
├── web/                               # React SPA — search bar + result list
│   ├── Dockerfile
│   └── src/{api,components}/
├── openspec/                         # Planning artifacts (OpenSpec)
│   ├── changes/
│   │   └── ci-cd-pipeline-setup/   # CI/CD proposal + specs
│   └── specs/
└── .claude/                          # Claude Code configuration
```

---

## 🏛️ Architecture Decision Records (ADRs)

See `docs/onionmind-master-sdd.md` section 4 for complete ADRs. Key decisions:

| ADR | Decision | Rationale |
|-----|----------|-----------|
| **ADR-001** | Modular monolith (Spring Modulith), not microservices | One person/pair doesn't pay coordination costs before needing independent scaling |
| **ADR-002** | Redpanda, not full Kafka | Single binary, fits one VM |
| **ADR-007** | Monorepo, not per-service repos | Reduces friction for small team; criteria for splitting defined explicitly |
| **ADR-008** | Java 25 + Spring Boot 4.1 | Toolchain isolates compilation, avoids daemon compatibility issues |
| **ADR-009** | In-process events (Modulith) + Kafka cross-process | Two mechanisms for two different boundaries |

---

## 🤝 Contributing

### Branch Strategy

- `main`: production-ready (requires approval + CI pass)
- `feature/*`: your work (feature branches from `main`)

### Workflow

1. **Create branch:**
   ```bash
   git checkout -b feature/your-feature
   ```

2. **Make changes** following the phase spec

3. **Run tests locally:**
   ```bash
   # Java
   cd core && ./gradlew clean test jacocoTestReport
   
   # Go
   cd crawler && make test-coverage
   ```

4. **Push and open PR**
   ```bash
   git push origin feature/your-feature
   # Open PR on GitHub
   ```

5. **CI runs automatically:**
   - Tests + coverage validation
   - SonarCloud analysis
   - Modulith boundary verification

6. **Wait for approval** (1 of 2 code owners)

7. **Merge** when all checks pass

### Coverage Requirements

Minimum **80% line coverage** required for both Java and Go.

**Check coverage locally:**
```bash
# Java
open core/build/reports/jacoco/test/html/index.html

# Go
cd crawler && go test -cover ./... | grep coverage
```

If below 80%:
```bash
# Add tests until coverage improves
cd core && ./gradlew test
cd ../crawler && go test -v ./...
```

### Code Style

- **Java:** Spring Boot conventions + checkstyle (enforced in CI)
- **Go:** gofmt + go vet
- **Commits:** Conventional commits (`feat:`, `fix:`, `docs:`, `test:`)

---

## 📚 Documentation

| Document | Content |
|----------|---------|
| `docs/onionmind-master-sdd.md` | Consolidated architecture, all ADRs, design patterns, roadmap |
| `docs/onionmind-fase1-sdd.md` | Phase 1 detailed specification (crawler, indexing, API) |
| `docs/onionmind-fase3-sdd.md` | Phase 3 detailed specification (AI orchestration, cost optimizer, guard) |
| `docs/onionmind-fase4-sdd.md` | Phase 4 detailed specification (entities, knowledge graph, change detection) |
| `docs/onionmind-mvp-architecture.md` | Architecture diagrams and data flows |

Each document is self-contained and links to others for cross-reference.

---

## 🔗 Resources

- **GitHub Repository:** https://github.com/carlosportella/onionmind
- **SonarCloud Dashboard:** https://sonarcloud.io/dashboard?id=onionmind-java
- **Docker Compose:** See `docker-compose.yml` for service versions and ports
- **Project Planning:** `openspec/changes/` for active proposals and changes

---

## 📝 License

[Your choice — add if applicable]

---

## Team

- **@carlosportella** — Core lead
- **@fepersilva** — Collaborator

**Built incrementally. One phase at a time. Always shippable.**
# Testing workflows
