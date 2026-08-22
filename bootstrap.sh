#!/usr/bin/env bash
# OnionMind — bootstrap da Fase 0 (v2, pós-Spring Initializr)
#
# Pré-requisito: já ter rodado o curl do Spring Initializr e descompactado
# em core/ (ver instruções acima). Este script NÃO toca em core/build.gradle,
# core/settings.gradle nem no gradlew — isso já veio pronto do Initializr.
set -euo pipefail

GO_MODULE_USER="carlosportella16"

if [ -f core/src/main/java/com/onionmind/content/processors/HtmlSanitizerProcessor.java ]; then
  echo "Fase 1 já em andamento — este script é só pra bootstrap inicial. Abortando."
  exit 1
fi

if [ ! -f core/gradlew ]; then
  echo "ERRO: core/gradlew não encontrado."
  echo "Rode o curl do Spring Initializr e descompacte em core/ antes deste script."
  exit 1
fi

echo "==> Criando diretórios restantes (crawler, db, docs, .github)..."
mkdir -p .github/workflows
mkdir -p db/migrations
mkdir -p docs
mkdir -p crawler/cmd/crawler
mkdir -p crawler/internal/connector
mkdir -p crawler/internal/dedup
mkdir -p crawler/internal/publisher
mkdir -p web
mkdir -p core/src/main/java/com/onionmind/content
mkdir -p core/src/main/java/com/onionmind/ai

# ---------------------------------------------------------------------------
echo "==> .gitignore (raiz)"
cat > .gitignore << 'EOF'
# Java / Gradle
core/build/
core/.gradle/
*.class

# Go
crawler/crawler
crawler/*.exe

# Node
web/node_modules/
web/dist/

# Env / IDE
.env
*.local
.idea/
.vscode/
*.iml
EOF

echo "==> README.md (raiz)"
cat > README.md << 'EOF'
# OnionMind

> AI-powered Knowledge Discovery Platform, initially focused on the Tor Network.

**Status:** Fase 0 — Fundação.

Veja `docs/onionmind-sdd.md` para o roadmap completo e as decisões
arquiteturais (ADRs), e `docs/onionmind-fase1-sdd.md` para o detalhamento
da fase em andamento.
EOF

echo "==> docs/README.md (placeholder)"
cat > docs/README.md << 'EOF'
Copie para cá os SDDs já gerados:
- onionmind-sdd.md
- onionmind-fase1-sdd.md
- onionmind-descoberta-indexacao.md
EOF

# ---------------------------------------------------------------------------
echo "==> docker-compose.yml"
cat > docker-compose.yml << 'EOF'
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: onionmind
      POSTGRES_USER: onionmind
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-onionmind_dev_only}
    ports: ["5432:5432"]
    volumes:
      - pg_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U onionmind"]
      interval: 5s
      retries: 5

  redpanda:
    image: redpandadata/redpanda:v24.2.7
    command:
      - redpanda
      - start
      - --smp=1
      - --memory=512M
      - --overprovisioned
      - --node-id=0
      - --kafka-addr=PLAINTEXT://0.0.0.0:9092
      - --advertise-kafka-addr=PLAINTEXT://redpanda:9092
    ports: ["9092:9092"]
    healthcheck:
      test: ["CMD", "rpk", "cluster", "info"]
      interval: 5s
      retries: 5

  redpanda-console:
    image: redpandadata/console:v2.7.2
    environment:
      KAFKA_BROKERS: redpanda:9092
    ports: ["8080:8080"]
    depends_on: [redpanda]

volumes:
  pg_data:
EOF

# ---------------------------------------------------------------------------
echo "==> .github/CODEOWNERS + workflows"
cat > .github/CODEOWNERS << EOF
/crawler/                  @${GO_MODULE_USER}
/core/                     @${GO_MODULE_USER}
/web/                      @${GO_MODULE_USER}
docs/*.md                  @${GO_MODULE_USER}
EOF

cat > .github/workflows/ci-go.yml << 'EOF'
name: CI (Go)
on:
  push: { branches: [main], paths: ['crawler/**'] }
  pull_request: { paths: ['crawler/**'] }
jobs:
  go:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with: { go-version: '1.23' }
      - run: go vet ./... && go test ./...
        working-directory: crawler
EOF

cat > .github/workflows/ci-java.yml << 'EOF'
name: CI (Java)
on:
  push: { branches: [main], paths: ['core/**', 'db/**'] }
  pull_request: { paths: ['core/**', 'db/**'] }
jobs:
  java:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25' }
      - run: ./gradlew checkstyleMain test
        working-directory: core
EOF

# ---------------------------------------------------------------------------
echo "==> db/migrations/V1__initial_schema.sql"
cat > db/migrations/V1__initial_schema.sql << 'EOF'
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
EOF

# ---------------------------------------------------------------------------
echo "==> ajustando core/src/main/resources/application.yml"
# O Initializr gera application.properties por padrão — trocamos por .yml,
# mantendo o padrão já usado nos SDDs.
rm -f core/src/main/resources/application.properties
cat > core/src/main/resources/application.yml << 'EOF'
spring:
  application:
    name: onionmind-core
  datasource:
    url: jdbc:postgresql://localhost:5432/onionmind
    username: onionmind
    password: ${POSTGRES_PASSWORD:onionmind_dev_only}
  flyway:
    enabled: true
    locations: classpath:db/migrations

server:
  port: 8081
EOF

# ---------------------------------------------------------------------------
echo "==> módulo Go (crawler)"
(cd crawler && go mod init "github.com/${GO_MODULE_USER}/onionmind/crawler")

cat > crawler/internal/connector/connector.go << 'EOF'
package connector

import "context"

// SourceConnector é a abstração genérica para fontes de conteúdo (ADR-004).
// TorConnector (Fase 1) é a primeira implementação.
type SourceConnector interface {
	ID() string
	Fetch(ctx context.Context, url string) (*RawPage, error)
}

type RawPage struct {
	URL        string `json:"url"`
	SourceType string `json:"source_type"`
	HTML       []byte `json:"html"`
	FetchedAt  string `json:"fetched_at"`
}
EOF

cat > crawler/cmd/crawler/main.go << 'EOF'
package main

import "log/slog"

func main() {
	// Implementação real (worker pool, TorConnector, frontier) chega na Fase 1.
	slog.Info("onionmind crawler — fase 0, aguardando implementação")
}
EOF

# ---------------------------------------------------------------------------
echo "==> módulo content (Spring Modulith) — específico do OnionMind"
cat > core/src/main/java/com/onionmind/content/package-info.java << 'EOF'
/**
 * Pipeline de plugins de processamento — ContentProcessor (SDD seção 7.1).
 */
@org.springframework.modulith.ApplicationModule
package com.onionmind.content;
EOF

cat > core/src/main/java/com/onionmind/content/DocumentType.java << 'EOF'
package com.onionmind.content;

public enum DocumentType { HTML, PDF, IMAGE }
EOF

cat > core/src/main/java/com/onionmind/content/Document.java << 'EOF'
package com.onionmind.content;

public record Document(
    String url, String sourceType, String rawHtml, String extractedText, DocumentType type
) {
    public Document withExtractedText(String text) {
        return new Document(url, sourceType, rawHtml, text, type);
    }
}
EOF

cat > core/src/main/java/com/onionmind/content/ProcessingResult.java << 'EOF'
package com.onionmind.content;

public record ProcessingResult(Document document, Status status, String error) {
    public enum Status { SUCCESS, SKIPPED, FAILED }

    public static ProcessingResult success(Document doc) { return new ProcessingResult(doc, Status.SUCCESS, null); }
    public static ProcessingResult unchanged(Document doc) { return new ProcessingResult(doc, Status.SKIPPED, null); }
    public static ProcessingResult skipped(Document doc, String reason) { return new ProcessingResult(doc, Status.SKIPPED, reason); }
    public static ProcessingResult failed(Document doc, String error) { return new ProcessingResult(doc, Status.FAILED, error); }
}
EOF

cat > core/src/main/java/com/onionmind/content/ContentProcessor.java << 'EOF'
package com.onionmind.content;

public interface ContentProcessor {
    ProcessingResult process(Document document);
    boolean supports(DocumentType type);
    default int order() { return 0; }
}
EOF

cat > core/src/main/java/com/onionmind/content/NoOpContentProcessor.java << 'EOF'
package com.onionmind.content;

import org.springframework.stereotype.Component;

@Component
public class NoOpContentProcessor implements ContentProcessor {
    public ProcessingResult process(Document document) { return ProcessingResult.unchanged(document); }
    public boolean supports(DocumentType type) { return false; }
}
EOF

echo "==> módulo ai (Spring Modulith) — específico do OnionMind"
cat > core/src/main/java/com/onionmind/ai/package-info.java << 'EOF'
/**
 * Abstração sobre provedores de IA — AIOrchestrator (SDD seção 7.2).
 * Implementação real (Ollama/Groq/Gemini + decorator chain) chega na Fase 3.
 */
@org.springframework.modulith.ApplicationModule
package com.onionmind.ai;
EOF

cat > core/src/main/java/com/onionmind/ai/TaskContext.java << 'EOF'
package com.onionmind.ai;

public record TaskContext(
    TaskType type, int approxTokens, String sourceLanguage,
    boolean critical, boolean interactive, Double previousConfidence, int attemptNumber
) {
    public enum TaskType { SUMMARIZE, CLASSIFY, TRANSLATE, EXTRACT_ENTITIES, EMBED }
}
EOF

cat > core/src/main/java/com/onionmind/ai/Summary.java << 'EOF'
package com.onionmind.ai;

public record Summary(String text, double confidence) {}
EOF

cat > core/src/main/java/com/onionmind/ai/Embedding.java << 'EOF'
package com.onionmind.ai;

public record Embedding(float[] vector, double confidence) {}
EOF

cat > core/src/main/java/com/onionmind/ai/AIOrchestrator.java << 'EOF'
package com.onionmind.ai;

public interface AIOrchestrator {
    Summary summarize(String text, TaskContext ctx);
    Embedding embed(String text, TaskContext ctx);
}
EOF

cat > core/src/main/java/com/onionmind/ai/NoOpAIOrchestrator.java << 'EOF'
package com.onionmind.ai;

import org.springframework.stereotype.Component;

@Component
public class NoOpAIOrchestrator implements AIOrchestrator {
    public Summary summarize(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("IA chega na Fase 3 — ver SDD seção 4.2");
    }
    public Embedding embed(String text, TaskContext ctx) {
        throw new UnsupportedOperationException("IA chega na Fase 3 — ver SDD seção 4.2");
    }
}
EOF

# ---------------------------------------------------------------------------
echo ""
echo "==> Estrutura da Fase 0 criada (core/ já vinha do Spring Initializr)."
echo ""
echo "Próximos passos:"
echo "  1. Copie os SDDs já gerados para docs/"
echo "  2. Confira se core/build.gradle inclui flyway-database-postgresql"
echo "     (Flyway 10+ separou o suporte a Postgres nesse artefato)"
echo "  3. docker compose up -d"
echo "  4. cd core && ./gradlew bootRun   # já tem o wrapper, não precisa de Gradle instalado"
echo "  5. git add -A && git commit -m 'Fase 0: fundação (Initializr + OnionMind scaffold)'"