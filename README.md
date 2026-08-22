# OnionMind

> AI-powered Knowledge Discovery Platform for the Tor Network.

**Current Phase:** Phase 1 complete (crawler discovers `.onion` pages via Tor, ingestion pipeline sanitizes and versions content, full-text search API + React SPA) — Phase 2 (semantic search) next

**Tech Stack:** Go (crawler) • Java 25 (Spring Boot 4.1 + Spring Modulith) • PostgreSQL • Redpanda • React

---

## 🎯 Overview

OnionMind discovers, crawls, indexes, and analyzes `.onion` content, transforming unstructured pages into searchable knowledge via AI.

### Why?

Most Tor search engines are link lists without ranking or context. OnionMind changes that:
- **Continuous Discovery:** Go crawler autonomously finds new pages via Tor
- **Intelligent Indexing:** Full-text search (PostgreSQL), semantic search (Qdrant), knowledge graph (Neo4j)
- **Free AI:** Summaries, classification, translation, entity extraction — locally via Ollama + free tiers (Groq, Gemini Flash)
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
| **2** | Semantic search (embeddings + Qdrant) | ⏳ Planned | Concept-based search works |
| **3** | AI generation (summarize, classify, translate) | ⏳ Planned | New page summarized in minutes, quota never exceeded |
| **4** | Knowledge graph + versioning | ⏳ Planned | "What changed?" answers correctly |
| **5** | Full RAG + Agent Playground | ⏳ Planned | RAG pipeline functional, real-world tested |
| **6** | External connectors (RSS, GitHub, PDF) | ⏳ Backlog | — |

### Phase 1 Details

Ships a functional Tor search engine without AI:
- **Crawler:** TorConnector discovers URLs via Tor, avoids traps, deduplicates with Redis
- **Sanitization:** HTML cleaned before any persistence (OWASP allowlist)
- **Indexing:** PostgreSQL `tsvector` GENERATED column + GIN index, `ts_rank` for relevance
- **REST API:** GET `/api/search?q=term` returns ranked pages
- **Frontend:** Minimal React SPA with search input

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

# Start infrastructure — postgres, redpanda, redpanda-console, redis, tor
docker-compose up -d

# Wait for services to report healthy
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

**Search directly against the API:** http://localhost:8081/api/search?q=bitcoin

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
│   ├── cmd/crawler/main.go
│   └── internal/{config,connector,dedup,frontier,normalizer,publisher,worker}/
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
