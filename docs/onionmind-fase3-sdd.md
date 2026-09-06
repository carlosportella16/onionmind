# OnionMind — Fase 3: IA Generativa + Cost Optimizer (SDD)

**Pré-requisito:** Fase 2 concluída (embeddings via Ollama, Qdrant, fusão full-text + vetorial) — ver "Pendências da Fase 2" na seção 2, algumas precisam fechar antes do gate desta fase.
**Objetivo:** adicionar entendimento gerado por LLM (resumo, classificação, tradução) sobre o pipeline de ingestão, sem acoplar o sistema a um provedor específico nem a uma conta paga.
**Semanas estimadas:** 9–13 (referência `onionmind-sdd.md` seção 5).
**Status:** código completo e testado na branch `feat/phase-3-llm-integration` (OpenSpec change `phase3-ai-generation`). DoD detalhada na seção 12; gate real ainda pendente (seção 11.4). Prontidão para a Fase 4 na seção 13.

Documentos de referência (validados para esta fase):
- `onionmind-sdd.md` — seções 5 (Fase 3), 7.2 (`AIOrchestrator`), 7.3 (Cost Optimizer), 7.4 (confidence), 8 (segurança Fase 3+), 9 (LLM gratuito), 14 (DoD Fase 3)
- `onionmind-master-sdd.md` — seções 6.2, 6.3, 6.4, 6.5, 11, 12
- `onionmind-fase1-sdd.md` — seções 6.4 (`IngestionPipeline`), 6.5 (`Document`), 6.7 (`PageRepository`)

---

## 1. O que essa fase entrega (e o que não entrega)

**Entrega — OnionMind v0.3:** toda página nova indexada é automaticamente resumida, classificada por categoria e (quando não está em português) traduzida, com um valor de `confidence` gravado junto de cada campo. O provedor de IA é escolhido automaticamente por um roteador de custo; sob uso normal, nenhum tier gratuito estoura. A busca (Fase 1 + 2) passa a devolver cada resultado já com resumo e categoria — sem chamar LLM ao vivo (SDD seção 9: `/api/search` nunca chama LLM, os campos vêm prontos da ingestão).

**Não entrega:**
- Chat / RAG / streaming de resposta (Fase 5).
- `EntityProcessor` / knowledge graph / Neo4j (Fase 4). A interface `AIOrchestrator` reserva `extractEntities(...)` mas ela permanece sem implementação real nesta fase.
- Diff estrutural sumarizado entre versões (Fase 4 — reusa o LLM que esta fase disponibiliza).
- Fine-tuning, avaliação automatizada de qualidade de prompt, A/B de modelos.
- Hashing perceptual de **imagens** — o pipeline não persiste imagens; ver seção 7.5 para o que o filtro de conteúdo ilegal efetivamente cobre nesta fase.

**Por que multi-provider desde já:** cada tier gratuito tem um limite diferente e muda com frequência (SDD seção 9, ressalva). Acoplar a um só provedor significa que uma mudança de política externa quebra a ingestão inteira. Os três adapters mais o fallback local (Ollama, sem quota) garantem que o pipeline nunca para por falta de cota.

---

## 2. Pendências da Fase 2 a fechar (para o produto final)

A Fase 2 entregou **código** de busca semântica, mas com lacunas de validação e de configuração que precisam ser resolvidas — as duas primeiras **antes** do gate da Fase 3, porque o gate depende de IA rodando de verdade contra o corpus real.

| # | Pendência | Estado atual | Ação nesta fase |
|---|-----------|--------------|-----------------|
| P2-1 | Gate de saída da Fase 2 nunca validado ponta a ponta contra Ollama + Qdrant reais | `Fase2SemanticSearchGateTest` usa fakes (o próprio teste declara isso); não há registro de validação manual como a Fase 1 tem (`fase1-sdd.md` seção 9) | Rodar o ciclo real: subir Ollama + Qdrant, `embedding.enabled=true`, crawlear um corpus `.onion` pequeno, confirmar que uma busca por conceito acha o que a full-text não acha. Documentar tempo e ajustes (padrão da Fase 1). |
| P2-2 | `min-score` (piso de similaridade coseno) é um chute (`0.35`) não calibrado contra corpus real | `application.yml` — comentário admite "tune per embedding model/corpus" | Calibrar contra o corpus de P2-1: medir a distribuição de score de pares relevantes vs. irrelevantes, ajustar o piso, registrar o número observado. |
| P2-3 | Semantic search desligado por padrão; `application-sandbox.yml` e `application-prod.yml` divergem (sandbox não tem bloco de IA nenhum) | `embedding.enabled: false` no `application.yml`; sandbox sem `ollama`/`qdrant`/`embedding` | Definir o default de produção (ver Decisão D1) e alinhar os três profiles. |
| P2-4 | Fase 2 não tem OpenSpec change nem registro de conclusão | `openspec list` mostra só `phase1-crawler-search` | Registrar retroativamente: nota no README / `master-sdd.md` de que a Fase 2 fechou, com link para o(s) PR(s) (#5, #6) e o resultado de P2-1. |
| P2-5 | `BadSqlGrammarException` na `EVENT_PUBLICATION` (Event Publication Registry) no shutdown hook | Aparece nos logs de teste; build verde, só no destroy | Investigar antes da Fase 4 (que usa eventos in-process de verdade). Não bloqueia a Fase 3. Rastrear como item separado. |
| P2-6 | `PageRepository` nunca escreve as colunas `summary` / `category` / `language` / `entities` (existem desde `V1`) | `upsertWithVersioning` só toca texto/hash/versão/`embedding_status` | Fechado naturalmente pela seção 7.7 desta fase. |

**Regra de corte:** P2-1, P2-2 e P2-3 são pré-condição do gate da Fase 3. P2-4 e P2-5 podem correr em paralelo. P2-6 é trabalho da própria fase.

---

## 3. Arquitetura da Fase 3

```
                    ┌───────────────────────────┐
                    │   React SPA (sem mudança)   │
                    │  resultado já traz resumo   │
                    │  + categoria (JSONB)        │
                    └────────────┬──────────────┘
                                 │
                    ┌────────────▼──────────────────────────┐
                    │           Monolito Java                 │
                    │         (Spring Modulith)               │
                    │                                         │
                    │  ┌───────────────────────────────────┐  │
                    │  │ ingestion — IngestionPipeline      │  │
                    │  └───────────────┬───────────────────┘  │
                    │                  │ itera por order()     │
                    │  ┌───────────────▼───────────────────┐  │
                    │  │ content                            │  │
                    │  │  IllegalContentGuard        (5)    │  │
                    │  │  LanguageDetectorProcessor  (10)   │  │
                    │  │  TranslationProcessor       (20)   │  │
                    │  │  SummarizerProcessor        (30)   │  │
                    │  │  ClassifierProcessor        (40)   │  │
                    │  │  EmbeddingProcessor         (100)  │  │
                    │  └───────────────┬───────────────────┘  │
                    │                  │ .summarize/.translate/.classify
                    │  ┌───────────────▼───────────────────┐  │
                    │  │ ai — DefaultAIOrchestrator         │  │
                    │  │   Metrics→Cache→Retry→Router→      │  │
                    │  │   Provider→Validation (escalona)   │  │
                    │  │                                    │  │
                    │  │   AIProvider: Ollama│Groq│Gemini   │  │
                    │  │   CostOptimizerRouter              │  │
                    │  │   ProviderQuotaTracker ──► Redis   │  │
                    │  └───────────────┬───────────────────┘  │
                    └──────────────────┼─────────────────────┘
                        r/w │          │ HTTP
                    ┌───────▼──────┐  ┌▼──────────────┬───────────────┐
                    │  PostgreSQL   │  │ Ollama (local)│ Groq / Gemini │
                    │ pages.summary │  │  llama3.1:8b  │  (nuvem, free) │
                    │ pages.category│  │  + embeddings │                │
                    │ pages.language│  └───────────────┴───────────────┘
                    │ ai_status     │
                    └──────────────┘
```

**Componentes novos nesta fase (não existiam na Fase 2):**

- `DefaultAIOrchestrator` — implementação real da interface, substitui `NoOpAIOrchestrator` e `OllamaAIOrchestrator` (o `embed()` da Fase 2 é absorvido; ver seção 7.1).
- `AIProvider` + três adapters (`OllamaProvider`, `GroqProvider`, `GeminiProvider`).
- Decorator chain: `MetricsDecorator`, `CacheDecorator`, `RetryDecorator`, `ValidationDecorator`.
- `CostOptimizerRouter` + `ProviderQuotaTracker` (Redis — nova dependência do módulo `ai`).
- `IllegalContentGuard` — barreira de conteúdo ilegal antes de qualquer LLM.
- Quatro `ContentProcessor` novos: `LanguageDetectorProcessor`, `TranslationProcessor`, `SummarizerProcessor`, `ClassifierProcessor`.
- Migration `V4__ai_generation.sql` (tracking de status de IA + quarentena).
- `pages.summary` / `category` / `language` passam a ser escritas (colunas já existiam).
- Redis vira dependência do `core` (até aqui só o crawler Go usava Redis).
- Modelo Ollama generativo (`llama3.1:8b` ou equivalente) puxado no `docker-compose` — o `nomic-embed-text` da Fase 2 só faz embeddings.

---

## 4. Estrutura de diretórios (final da Fase 3)

```
core/src/main/java/com/onionmind/
├── ai/
│   ├── package-info.java              # allowedDependencies: só infra (Redis), sem outros módulos
│   ├── AIOrchestrator.java            # + classify(), translate() na interface
│   ├── DefaultAIOrchestrator.java     # decorator chain + loop de escalonamento
│   ├── TaskContext.java               # já existe — + campo previousConfidence usado de verdade
│   ├── Summary.java / Classification.java / Translation.java   # records {valor, confidence}
│   ├── AllProvidersExhaustedException.java
│   ├── provider/
│   │   ├── AIProvider.java
│   │   ├── CompletionRequest.java     # prompt + system + params
│   │   ├── CompletionResponse.java    # texto cru + metadados de uso
│   │   ├── ProviderQuota.java
│   │   ├── OllamaProvider.java        # http://localhost:11434/api/generate
│   │   ├── GroqProvider.java          # api.groq.com/openai/v1 (OpenAI-compatible)
│   │   └── GeminiProvider.java        # generativelanguage.googleapis.com/v1beta
│   ├── routing/
│   │   ├── CostOptimizerRouter.java
│   │   └── ProviderQuotaTracker.java  # Redis: diário + por minuto
│   └── decorator/
│       ├── MetricsDecorator.java
│       ├── CacheDecorator.java        # Redis, TTL curto por (request, provider)
│       ├── RetryDecorator.java        # backoff, mesmo provider, falha transiente
│       └── ValidationDecorator.java   # confidence ≥ 0.7 e JSON válido
│
├── content/
│   ├── guard/
│   │   └── IllegalContentGuard.java   # ContentProcessor order 5
│   └── processors/
│       ├── HtmlSanitizerProcessor.java   # order 0 (já existe)
│       ├── LanguageDetectorProcessor.java # order 10
│       ├── TranslationProcessor.java      # order 20
│       ├── SummarizerProcessor.java       # order 30
│       ├── ClassifierProcessor.java       # order 40
│       └── EmbeddingProcessor.java        # order 100 (já existe)
│
└── ingestion/
    └── internal/
        └── PageRepository.java        # + persistência dos campos de IA (seção 7.7)

db/migrations/
├── V1__initial_schema.sql
├── V2__fulltext_search.sql
├── V3__embeddings.sql
└── V4__ai_generation.sql             # novo

docker-compose.yml                     # + ollama-pull do modelo generativo
```

---

## 5. Configuração

### 5.1 `application.yml` (adições)

```yaml
ai:
  enabled: false            # liga o pipeline generativo — separado de embedding.enabled (Decisão D1)
  min-confidence: 0.7       # abaixo disso o ValidationDecorator escala pro próximo provider
  max-escalations: 2        # tentativas de troca de provider antes de AllProvidersExhausted
  cache-ttl: 24h            # CacheDecorator (Redis)
  ollama:
    generate-model: llama3.1:8b
    timeout: 120s           # geração local é lenta numa VM pequena
  groq:
    base-url: https://api.groq.com/openai/v1
    model: llama-3.3-70b-versatile
    api-key: ${GROQ_API_KEY:}
    daily-limit: 14400
    minute-limit: 30
  gemini:
    base-url: https://generativelanguage.googleapis.com/v1beta
    model: gemini-2.5-flash
    api-key: ${GEMINI_API_KEY:}
    daily-limit: 1500
    minute-limit: 15

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

illegal-content:
  url-denylist-path: classpath:security/onion-denylist.txt
  text-pattern-path: classpath:security/illegal-text-patterns.txt
  quarantine-retention-days: 30    # metadados mínimos p/ auditoria, conteúdo não é armazenado
```

- **Limites de tier são de agosto de 2026** (SDD seção 9, ressalva) — reconferir antes de produção. Ficam em config, não hard-coded.
- `GROQ_API_KEY` / `GEMINI_API_KEY` **só via variável de ambiente**, nunca no repositório. Ausentes → o provider correspondente reporta quota zero e o router simplesmente o pula (degrada pra Ollama).

### 5.2 `application-sandbox.yml` / `application-prod.yml`

Alinhar os três profiles (P2-3). Sandbox e prod recebem o bloco `ai` completo; a diferença é só `ai.enabled` e as chaves (Decisão D1).

### 5.3 `docker-compose.yml`

```yaml
  ollama-pull-generate:
    image: ollama/ollama:latest
    depends_on:
      ollama:
        condition: service_healthy
    entrypoint: ["/bin/sh", "-c"]
    command: ["OLLAMA_HOST=ollama:11434 ollama pull llama3.1:8b"]
    restart: "no"
```

Redis já está no compose desde a Fase 1 (dedup do crawler) — a mesma instância serve o `core`. Keyspaces distintos por prefixo (`quota:*`, `ai-cache:*` vs. o `dedup:*` do crawler).

---

## 6. Migration Flyway — `V4__ai_generation.sql`

```sql
-- Fase 3: rastreio do ciclo de geração de IA por página + quarentena de conteúdo ilegal.
-- Os campos gerados (summary, category, language) já existem como JSONB desde V1 —
-- esta migration só adiciona o tracking de status, espelhando o padrão de V3 (embeddings).

ALTER TABLE pages ADD COLUMN ai_status VARCHAR(20) NOT NULL DEFAULT 'pending';
-- 'pending' | 'processed' | 'unchanged' | 'failed_transient' | 'failed_permanent' | 'quarantined'
ALTER TABLE pages ADD COLUMN ai_processed_at TIMESTAMPTZ;
ALTER TABLE pages ADD COLUMN ai_error_message TEXT;

CREATE INDEX idx_pages_ai_status ON pages (ai_status);

-- Auditoria mínima de páginas barradas pelo IllegalContentGuard. Sem extracted_text,
-- sem raw_html — só o suficiente pra provar por que foi descartada e permitir revisão.
CREATE TABLE quarantined_pages (
    id            BIGSERIAL PRIMARY KEY,
    url           TEXT NOT NULL,
    content_hash  TEXT NOT NULL,
    reason        TEXT NOT NULL,          -- 'url-denylist' | 'text-pattern:<id>'
    detected_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_quarantined_pages_url ON quarantined_pages (url);
```

O backfill de IA para páginas já indexadas antes da Fase 3 (`ai_status = 'pending'`) reusa o padrão do `EmbeddingBackfillJob` da Fase 2 — job agendado, `batch-size` configurável, respeitando o Cost Optimizer.

---

## 7. Componentes

### 7.1 `AIOrchestrator` — interface expandida

```java
public interface AIOrchestrator {
    Summary summarize(String text, TaskContext ctx);
    Classification classify(String text, TaskContext ctx);
    Translation translate(String text, String targetLang, TaskContext ctx);
    Embedding embed(String text, TaskContext ctx);
    // extractEntities(...) fica reservado pra Fase 4 — não implementado aqui
}

public record Summary(String text, double confidence) {}          // já existe
public record Classification(String category, double confidence) {}
public record Translation(String text, String detectedLanguage, double confidence) {}
```

`DefaultAIOrchestrator` substitui `NoOpAIOrchestrator` e `OllamaAIOrchestrator`:

- `summarize` / `classify` / `translate` → passam pela decorator chain + loop de escalonamento (seção 7.3).
- `embed` → vai direto ao `OllamaProvider` (embeddings têm um provedor só na Fase 3; sem escada, sem validação de confidence — o modelo é determinístico, `confidence = 1.0`, comportamento idêntico ao da Fase 2).
- Gate de ativação: `@ConditionalOnProperty("ai.enabled")`. Quando `false`, um `NoOpAIOrchestrator` continua registrado (lança `UnsupportedOperationException` — igual hoje) para que o `EmbeddingProcessor` da Fase 2 e o contexto Spring subam sem exigir chaves de API.

### 7.2 `AIProvider` + adapters

```java
public interface AIProvider {
    String id();                                  // "ollama" | "groq" | "gemini"
    CompletionResponse complete(CompletionRequest request);
    ProviderQuota currentQuota();
}
```

| Adapter | Endpoint | Modelo (config) | Quota | Papel |
|---------|----------|-----------------|-------|-------|
| `OllamaProvider` | `POST /api/generate` (local) | `llama3.1:8b` | ilimitada | fallback final, texto curto não-crítico |
| `GroqProvider` | `POST /chat/completions` (OpenAI-compat) | `openai/gpt-oss-120b` | diária + por minuto | 1ª opção p/ texto longo/crítico — latência baixíssima |
| `GeminiProvider` | `POST /models/{model}:generateContent` | `gemini-2.5-flash` | diária mais apertada, contexto 1M | 2ª opção — cobre textos que estouram contexto dos outros |

Adapters usam `RestClient` (Spring 6). Sem SDK proprietário — Groq é OpenAI-compatible, Gemini é REST simples. Cada um mapeia sua resposta pro `CompletionResponse` comum. Erro HTTP 429 → o adapter marca a cota como esgotada no `ProviderQuotaTracker` e o router segue pra frente.

### 7.3 Decorator chain + loop de escalonamento

```
Processor chama orchestrator.summarize(text, ctx)
  → MetricsDecorator          registra latência e provider escolhido (Micrometer)
    → CacheDecorator          Redis: hit por (hash do texto, tipo de tarefa) → retorna
      → [ loop de escalonamento, até ai.max-escalations ]
          RetryDecorator      backoff em falha transiente — MESMO provider
            → CostOptimizerRouter.select(ctx)   → AIProvider.complete()
          ValidationDecorator confidence ≥ ai.min-confidence  E  JSON válido?
            ├─ sim → grava no cache, retorna
            └─ não → ctx.withPreviousConfidence(c), próximo da escada (attempt++)
      última tentativa esgotada → retorna o melhor resultado obtido, marca confidence baixo
                                  (nunca lança pro pipeline — página fica com ai_status
                                   'failed_permanent' e é re-tentável pelo backfill)
```

`RetryDecorator` (rede/timeout, mesmo provider) e o loop de escalonamento (qualidade ruim, troca de provider) são **mecanismos distintos** — não misturar (SDD seção 7.2).

### 7.4 `CostOptimizerRouter` + `ProviderQuotaTracker`

```java
List<AIProvider> ladderFor(TaskContext ctx) {
    // Fase 3 é sempre batch (não-interativo). Interativo (Groq/Gemini primeiro) chega na Fase 5.
    return (ctx.approxTokens() < 500 && !ctx.critical())
        ? List.of(ollama, groq, gemini)
        : List.of(groq, gemini, ollama);
}

AIProvider select(TaskContext ctx) {
    return ladderFor(ctx).stream()
        .filter(p -> p.currentQuota().remaining() > SAFETY_MARGIN)
        .findFirst()
        .orElse(ollama);            // Ollama nunca tem quota — é sempre o piso
}
```

`ProviderQuotaTracker` no Redis, **diário e por minuto** (o teto por minuto estoura primeiro sob concorrência real — `master-sdd.md` seção 6.3):

```
quota:{provider}:day:{yyyy-MM-dd}     INCR, expira em 26h
quota:{provider}:minute:{epochMin}    INCR, expira em 90s
```

`SAFETY_MARGIN` (config, ex. 5% do limite diário) evita que o último request antes do teto vaze.

### 7.5 `IllegalContentGuard` — barreira antes de qualquer LLM

**Decisão de escopo (D3):** o SDD fala em "hashing perceptual contra bases conhecidas". Bases perceptuais de imagem (tipo PhotoDNA) não são acessíveis em tier gratuito **e** o pipeline não persiste imagens — só texto sanitizado. Então nesta fase o guard é textual e por URL, não perceptual de imagem:

1. **Denylist de URL/domínio** — lista curada de serviços `.onion` conhecidos (`onion-denylist.txt`). Match exato de host → quarentena imediata.
2. **Padrões de texto** — conjunto pequeno e auditado de regex/keywords para as categorias mais graves (`illegal-text-patterns.txt`, fora do controle de código para revisão separada). Match → quarentena.
3. Hashing perceptual de imagem fica registrado como **lacuna conhecida** para quando/se o pipeline passar a guardar mídia (Fase 6+ com conectores de PDF/imagem).

`IllegalContentGuard implements ContentProcessor`, `order() == 5` (depois do sanitizador, antes de tudo que chama IA — inclusive o `EmbeddingProcessor`, porque embedding também é processamento por modelo):

```java
public ProcessingResult process(Document doc) {
    var hit = detector.check(doc.url(), doc.extractedText());
    if (hit.isPresent()) {
        quarantineRepository.record(doc.url(), doc.resolvedContentHash(), hit.get().reason());
        return ProcessingResult.halt(doc, "quarantined: " + hit.get().reason()); // interrompe o pipeline
    }
    return ProcessingResult.success(doc);
}
```

`ProcessingResult.halt(...)` é um status novo (`HALT`) — diferente de `FAILED` (que só loga e segue). `IngestionPipeline` para de iterar ao ver `HALT`, marca `ai_status = 'quarantined'` e **não** persiste `extracted_text` nem `raw_html` para essa página (só a linha em `quarantined_pages`).

### 7.6 Processors dependentes de IA

Todos implementam `ContentProcessor`, `supports(HTML)`, e em falha retornam `ProcessingResult.failed(...)` (não bloqueiam o resto — `IngestionPipeline` da Fase 1 já trata isso). Gate de hash igual ao do `EmbeddingProcessor`: se `content_hash` não mudou, pula o processor inteiro (não gasta cota).

| order | Processor | O que faz | Provider típico |
|-------|-----------|-----------|-----------------|
| 10 | `LanguageDetectorProcessor` | Detecta idioma do `extractedText`. Biblioteca local (Lingua / Apache Tika) primeiro; só escala pro LLM em texto curto/ambíguo. Grava `pages.language = {"code": "en", "confidence": 0.98}`. | nenhum (local) |
| 20 | `TranslationProcessor` | Se `language.code != "pt"`, traduz pra PT. Guarda a tradução como campo separado (não sobrescreve `extracted_text` — o original continua sendo a fonte de verdade pra busca e re-embedding). | Groq → Gemini → Ollama |
| 30 | `SummarizerProcessor` | Resumo de 2–3 frases. `pages.summary = {"text": "...", "confidence": 0.91}`. Usa o texto traduzido se houver, senão o original. | Ollama (curto) → Groq → Gemini |
| 40 | `ClassifierProcessor` | Classifica numa taxonomia fixa e pequena (ex.: marketplace, fórum, blog, serviço, institucional, outro). Prompt pede JSON `{"category": "...", "confidence": 0.x}`. `critical = false`. | Ollama → Groq → Gemini |

`TaskContext` montado por cada processor: `approxTokens = text.length() / 4`, `sourceLanguage` da detecção, `critical = false` (nenhuma dessas tarefas é crítica na Fase 3 — o guard já rodou), `attemptNumber = 0`.

### 7.7 Persistência dos campos de IA (fecha P2-6)

Segue o padrão que a Fase 2 já usa para embeddings (`EmbeddingOutcome` carregado pelo `IngestionPipeline` e passado ao `PageRepository`), **não** engorda o record `Document`:

```java
// content/AiOutcome.java  — agregado pelo IngestionPipeline a partir dos ProcessingResult
public record AiOutcome(
    LanguageField language, SummaryField summary,
    CategoryField category, TranslationField translation,
    String status, String errorMessage
) { /* from(List<ProcessingResult>) */ }
```

```java
// PageRepository — método novo, aditivo (não mexe em upsertWithVersioning)
public void updateAiFields(String url, AiOutcome outcome) {
    jdbc.update("""
        UPDATE pages
        SET summary = ?::jsonb, category = ?::jsonb, language = ?::jsonb,
            ai_status = ?, ai_processed_at = now(), ai_error_message = ?
        WHERE url = ?
        """, json(outcome.summary()), json(outcome.category()), json(outcome.language()),
             outcome.status(), outcome.errorMessage(), url);
}
```

`IngestionPipeline.process(...)` ganha, depois do loop de processors: se algum resultado veio de um processor de IA, agrega em `AiOutcome` e chama `repository.updateAiFields(...)` na mesma transação da `upsertWithVersioning`.

**Contrato da API de busca:** `SearchResult` passa a expor `summary` e `category` (lidos direto das colunas — `SearchController` já lê `pages` por SQL, sem depender de `ingestion`). Zero chamada de LLM no caminho de busca.

---

## 8. Decisões de design

| ID | Decisão | Escolha | Razão |
|----|---------|---------|-------|
| D1 | `ai.enabled` separado de `embedding.enabled` | Duas flags independentes; profiles alinhados | Hoje `embedding.enabled` decide embeddings **e** qual bean `AIOrchestrator` sobe. Fase 3 tem três eixos (embeddings, geração, quais chaves) — uma flag só não cobre. Prod pode querer embeddings sem geração, ou vice-versa durante rollout. |
| D2 | Como o output de IA chega no `PageRepository` | Agregado `AiOutcome` no pipeline (padrão do `EmbeddingOutcome`), não campos novos no `Document` | `Document` é o modelo que trafega entre processors; poluí-lo com campos que só o último passo usa acopla tudo. O padrão já existe na Fase 2. |
| D3 | Escopo do filtro de conteúdo ilegal | Denylist de URL + padrões de texto auditados; hashing perceptual de imagem = lacuna conhecida documentada | Bases perceptuais não são gratuitas e o pipeline não guarda imagem. O gate de segurança do SDD ("antes de qualquer LLM, sem atalho de MVP") é atendido para o conteúdo que **existe** no pipeline (texto). |
| D4 | ADR-003 — Avro + Schema Registry "quando a Fase 3 chegar" | **Adiar explicitamente.** Continuar com eventos JSON. | A Fase 3 não adiciona nenhum produtor novo no tópico `raw-pages` (só consumidores in-JVM). Schema Registry resolve evolução entre múltiplos produtores independentes — problema que só aparece na Fase 6 (conectores externos). Registrar a decisão para não reabrir. |
| D5 | `embed()` na Fase 3 | Continua single-provider (Ollama), sem escada, sem `ValidationDecorator` | Só há um provedor de embeddings na Fase 3. Meter embeddings no loop de escalonamento adiciona complexidade sem ganho. Comportamento idêntico ao da Fase 2. |
| D6 | Idioma-alvo da tradução | PT fixo nesta fase | O produto é para pesquisadores lusófonos (personas, `sdd.md` seção 2). Alvo configurável é trabalho de Fase 5+ (multi-tenant / preferência de usuário). |
| D7 | Cache de IA | Redis, TTL 24h, chave por `(hash do texto, tipo de tarefa)` — não por provider | Reprocessar a mesma página (re-crawl com hash igual já é barrado antes; isto cobre restart de job / backfill sobreposto). Não incluir o provider na chave: a resposta de qualquer provedor válido serve. |

---

## 9. Segurança (Fase 3+)

- `IllegalContentGuard` roda **antes** de qualquer processor de IA/embedding (seção 7.5). Sem atalho de MVP (SDD seção 8).
- Página em quarentena: `extracted_text` e `raw_html` **não** são persistidos. Só `quarantined_pages` (URL, hash, motivo, timestamp) para auditoria, com retenção configurável.
- Chaves de API (`GROQ_API_KEY`, `GEMINI_API_KEY`) só por ambiente. CI não as tem — testes usam fakes de `AIProvider`.
- Prompts enviados aos provedores de nuvem: são texto já sanitizado (OWASP, Fase 1). Nenhum HTML cru sai da VM.
- Rate limiting defensivo: o `ProviderQuotaTracker` protege contra estourar cota, mas também contra um bug de loop disparar milhares de chamadas — o teto por minuto é o circuit breaker efetivo.

---

## 10. Observabilidade (Fase 3+)

Ativa o que o SDD seção 11 previa para "Fase 3+": Micrometer → Prometheus.

Métricas mínimas desta fase:
- `ai_request_total{provider, task_type, outcome}` — outcome ∈ `ok` / `escalated` / `exhausted` / `transient_fail`.
- `ai_request_duration_seconds{provider, task_type}` — histograma (segundos, escala separada da busca em ms — SDD seção 15 do master).
- `ai_quota_remaining{provider}` — gauge, alimentado pelo `ProviderQuotaTracker`.
- `ai_confidence{task_type}` — histograma; queda sustentada = degradação de qualidade de prompt/modelo.
- `content_quarantined_total{reason}` — contador.
- `ingestion_ai_lag` — páginas com `ai_status = 'pending'` (backlog do backfill).

---

## 11. Testes

### 11.1 Unitários (sem rede)

- `CostOptimizerRouter` — escada correta por `(approxTokens, critical)`; pula provider sem cota; cai em Ollama quando todos estouram.
- `ProviderQuotaTracker` — INCR/expire diário e por minuto; `remaining()` correto na virada de minuto/dia (Redis via Testcontainers ou fake).
- `ValidationDecorator` — JSON malformado → confidence 0; confidence abaixo do piso dispara escalonamento; última tentativa retorna sem lançar.
- `RetryDecorator` — retenta só falha transiente, mesmo provider, respeita o teto de tentativas.
- Cada adapter (`OllamaProvider` / `GroqProvider` / `GeminiProvider`) — mapeamento de resposta e de erro 429, com servidor HTTP fake (MockWebServer / WireMock).
- `IllegalContentGuard` — URL na denylist → `HALT`; padrão de texto → `HALT`; conteúdo limpo → `SUCCESS`.
- Cada `ContentProcessor` novo — happy path, falha de IA não bloqueia pipeline, gate de `content_hash` pula o processor.

### 11.2 Integração (Testcontainers: Postgres + Redpanda + Redis)

- Pipeline completo com `AIProvider` **fake** (determinístico): evento `raw-pages` → página com `summary`, `category`, `language` preenchidos e `ai_status = 'processed'`.
- Página que casa a denylist → `ai_status = 'quarantined'`, linha em `quarantined_pages`, `extracted_text` nulo.
- `SearchController` devolve `summary` e `category` no `SearchResult` sem tocar em nenhum provider.
- Backfill: página pré-existente com `ai_status = 'pending'` é processada pelo job.

### 11.3 Gate de saída — `Fase3AiGenerationGateTest`

Espelha o teste de gate da Fase 1/2. Com `AIProvider` fake para o determinismo do CI, mas assertando o **comportamento do gate**: uma página nova entra no pipeline e sai resumida + classificada + (se não-PT) traduzida, com `confidence` gravado, e o roteador escolheu provider sem intervenção.

### 11.4 Validação manual (é o critério de aceite real da fase)

Contra Groq + Gemini reais (chaves de dev) + Ollama local:
1. Fechar P2-1/P2-2/P2-3 primeiro.
2. Crawlear um corpus `.onion` pequeno e variado (idiomas diferentes, categorias diferentes).
3. Cronometrar: da página aparecer em `pages` até `ai_status = 'processed'` com os três campos preenchidos — confirmar **poucos minutos** sob carga normal.
4. Rodar o suficiente para exercitar a troca de provider (esgotar a cota por minuto do Groq de propósito) e confirmar que cai pra Gemini/Ollama sem erro.
5. Confirmar que nenhum tier gratuito foi estourado (checar os contadores `quota:*` no Redis e os dashboards dos provedores).
6. Documentar tempo observado, ajustes de prompt, e a distribuição de `confidence` por tarefa. Se o gate não bater, abrir segue-adiante antes de fechar a fase.

---

## 12. Definition of Done — Fase 3

Estado da implementação `phase3-ai-generation` (branch `feat/phase-3-llm-integration`): código completo e testado (`./gradlew clean test jacocoTestReport` — 197 testes, 0 falhas, cobertura de linha 92%, `ModularityTest` verde). Itens marcados `[~]` estão implementados mas dependem de validação manual contra provedores reais (seção 11.4).

### Pendências da Fase 2 (pré-condição do gate)
- [x] P2-1: gate de saída da Fase 2 validado contra Ollama + Qdrant reais — **fechado 2026-09-06**, ver nota abaixo
- [x] P2-2: `min-score` calibrado contra corpus real — **fechado 2026-09-06** (0.35 → 0.45), ver nota abaixo
- [x] P2-3: `application.yml` / `-sandbox` / `-prod` alinhados; `ai.enabled` separado de `embedding.enabled` (D1)
- [x] P2-4: conclusão da Fase 2 registrada (README + master-sdd, link PRs #5/#6) — nota registra que o gate ao vivo ainda não foi re-executado
- [x] P2-5: `BadSqlGrammarException` da `EVENT_PUBLICATION` — **fechado** (`spring.modulith.events.jdbc.schema-initialization.enabled=true`), ver nota abaixo

### Módulo `ai`
- [x] `AIOrchestrator` expõe `summarize`, `classify`, `translate`, `detectLanguage`, `embed`; `DefaultAIOrchestrator` implementa via decorator chain + loop de escalonamento
- [x] Três adapters `AIProvider` (Ollama, Groq, Gemini) com mapeamento de resposta e tratamento de 429 (`markExhausted`)
- [x] `CostOptimizerRouter` roteia por `(tamanho, criticidade, cota restante)`; Ollama é sempre o piso
- [x] `ProviderQuotaTracker` (Redis) — limites diário e por minuto, com margem de segurança (`ai.safety-margin-percent`)
- [x] `ValidationDecorator` trata JSON malformado como confidence 0 e escala; `DefaultAIOrchestrator` nunca lança pro pipeline (retorna o melhor resultado)
- [x] `RetryDecorator` cobre só falha transiente contra o mesmo provider
- [x] `CacheDecorator` (Redis, TTL configurável) evita reprocessamento
- [x] `ai.enabled=false` → contexto sobe sem chaves de API; `NoOpAIOrchestrator` / `OllamaAIOrchestrator` por `@ConditionalOnExpression`

### Módulo `content`
- [x] `IllegalContentGuard` (order 5) barra por denylist de URL e padrões de texto; `HALT` interrompe o pipeline
- [x] Página em quarentena não persiste `extracted_text`/`raw_html`; registra em `quarantined_pages`
- [x] `LanguageDetectorProcessor` (10) grava `pages.language` — heurística local + escalonamento pro LLM
- [x] `TranslationProcessor` (20) traduz não-PT → PT em `pages.translated_text` (V5), sem sobrescrever `extracted_text`
- [x] `SummarizerProcessor` (30) grava `pages.summary` com confidence
- [x] `ClassifierProcessor` (40) grava `pages.category` numa taxonomia fixa; fora da taxonomia → `FAILED` (re-enfileira no backfill)
- [x] Cada processor respeita o gate de `content_hash` via `AiEnrichmentGate` (não gasta cota em conteúdo inalterado)

### Persistência e API
- [x] `V4__ai_generation.sql` + `V5__page_translation.sql` aplicadas
- [x] `PageRepository.updateAiFields(...)` persiste os campos de IA na mesma transação da ingestão
- [x] `AiEnrichmentBackfillJob` processa páginas `pending`/`failed_transient` reusando os mesmos processors
- [x] `SearchResult` expõe `summary` e `category` (lidos de JSONB nas duas queries); nenhuma chamada de LLM no caminho de busca

### Infra e qualidade
- [x] `docker-compose.yml` puxa `llama3.1:8b` (serviço `ollama-pull-generate`)
- [x] Redis é dependência do `core` (`spring-boot-starter-data-redis`); keyspaces `quota:*` / `ai-cache:*` isolados do `dedup:*` do crawler
- [x] Métricas Micrometer: `ai.request.duration{task,provider}`, `ai.request.total{provider,task,outcome}`, `ai.confidence{task}`, `ai.quota.remaining{provider}`, `content.quarantined.total{reason}`, `ingestion.ai.lag`
- [x] Cobertura ≥ 80% (linha 92%) mantida; `ModularityTest` verde (`ai` com `allowedDependencies = {}`)
- [x] CI verde sem chaves de API — `Fase3AiGenerationGateTest` usa `@Primary` fake de `AIOrchestrator`, `ProviderQuotaTracker`/`CacheDecorator` mockados

### Nota P2-5 — `BadSqlGrammarException` na `EVENT_PUBLICATION` — **fechado 2026-09-06**
No shutdown de qualquer `@SpringBootTest`, o `DisposableBeanAdapter` do `eventPublicationRegistry` (Spring Modulith) tentava um `SELECT ... FROM EVENT_PUBLICATION` que falhava com `bad SQL grammar` — a tabela não existia porque a Fase 3 ainda não usa eventos in-process. Corrigido com `spring.modulith.events.jdbc.schema-initialization.enabled=true` em `application.yml`. Confirmado: 0 ocorrências do warning numa suíte completa após a mudança (antes aparecia ao menos 1x por run).

### Validação real — 2026-09-06 (Groq + Gemini reais, Ollama local, chaves de dev revogadas após o teste)

Dois bugs de correção só apareceram rodando contra provedores de verdade — nenhum teste com fake/mock os pegava:

1. **`ProviderQuota.availableWithMargin` comparava a base errada.** `ProviderQuotaTracker.remaining()` devolvia `effective = min(cotaDiaRestante, cotaMinutoRestante)` emparelhado com o limite **diário**. Com os limites documentados (Groq 30/min, 14400/dia, margem 5% = 720), `30 > 720` nunca é verdade — Groq e Gemini eram excluídos do ladder **sempre**, mesmo com cota cheia no início do dia. Os testes unitários (`ProviderQuotaTrackerTest`, `GroqProviderTest`) usavam limites onde minuto e dia são próximos (ex. `ProviderLimits(100, 5)`), o que mascarava o problema. Corrigido em `ProviderQuotaTracker.remaining()`: agora empareia a sobra com o limite que de fato está no comando (minuto ou dia, o que for mais apertado no momento). Sem isso, o Cost Optimizer nunca usava a nuvem em produção — todo tráfego ia pro Ollama silenciosamente.
2. **Modelo Groq `llama-3.3-70b-versatile` foi descontinuado** (`404 model_not_found` em toda chamada real). Catálogo atual da Groq (checado via `/v1/models`) não tem mais esse nome. Trocado para `openai/gpt-oss-120b` em `application.yml` e `application-prod.yml` (`GROQ_MODEL` continua sobrescrevível).

Depois dos dois fixes, rodada de validação com 4 páginas reais (>500 tokens aprox., forçando o ladder `CLOUD_FIRST`), limites de minuto propositalmente apertados (`groq=2`, `gemini=1`) pra forçar o estouro:

- Groq: 4 chamadas reais bem-sucedidas (`CLASSIFY`, `SUMMARIZE`×2, `TRANSLATE`) antes de estourar a cota do minuto.
- Ao estourar, o router migrou pra Gemini automaticamente — 2 chamadas reais bem-sucedidas (`TRANSLATE`, `DETECT_LANGUAGE`), sem lançar exceção pro pipeline.
- Com Groq e Gemini estourados na mesma janela de minuto, o restante caiu pro Ollama local (`CLASSIFY`, `SUMMARIZE`, `TRANSLATE`) — o piso nunca ficou sem cota.
- Confiança observada (janela do teste): `SUMMARIZE` média ≈0.97, `CLASSIFY` ≈0.86, `TRANSLATE` ≈0.75, `DETECT_LANGUAGE` com cache hits média mais baixa (poucas amostras reais).
- 4/4 páginas terminaram `ai_status=processed`; nenhuma chamada chegou perto do limite diário real (14400 Groq / 1500 Gemini) — poucas dezenas de requests no total.
- Textos curtos (<500 tokens aprox.) confirmados indo direto pro Ollama (`LOCAL_FIRST`), como desenhado — cota de nuvem intocada nesses casos.

P2-1/P2-2 (gate da Fase 2 contra Qdrant real + calibração de `min-score`) **fechados numa rodada seguinte** — ver nota própria acima.

### Nota P2-1/P2-2 — gate da Fase 2 e calibração de `min-score` — **fechado 2026-09-06**

Rodado com `embedding.enabled=true` / `ai.enabled=false` (modo `OllamaAIOrchestrator`, só `embed()`), Ollama real (`nomic-embed-text`) e Qdrant real — mesmo cenário do `Fase2SemanticSearchGateTest`, mas sem os fakes de `VectorStore`/`AIOrchestrator`.

**Um terceiro bug de correção só apareceu aqui**, de novo por causa de um corpus real: `SemanticSearchService.search()` passava o `topK` do chamador direto como o `limit` bruto do Qdrant (nível de chunk, não de página). Uma página degenerada da base de dev (uma listagem de links do ahmia.fi, 427 dos 454 pontos da coleção — puro texto de URLs, sem linguagem natural) ocupava sozinha toda a janela de candidatos em qualquer `topK` até 100, empurrando páginas genuinamente relevantes pra fora do resultado antes mesmo do filtro de `min-score` rodar — a página-conceito do gate (ver abaixo) não aparecia nem pedindo `topK=500` (o controller limita a 100). Nenhum teste pegava porque o `FakeVectorStore` nos testes unitários nunca simula uma página com centenas de chunks. Corrigido em `SemanticSearchService`: busca um pool de candidatos bem maior que o pedido pelo chamador (`topK×10`, teto 500), deduplica por URL, só depois corta pro tamanho pedido.

**Gate P2-1** (com o fix acima): página `concept-only-validate.onion` inserida com texto que nunca contém o termo literal da query ("moeda digital anonima e blockchain descentralizado" vs. query `criptomoedas`) — confirmado via SQL que `search_vector @@ websearch_to_tsquery(...)` é `false` (full-text estruturalmente não acha). `/api/search/semantic?q=criptomoedas` e `/api/search?q=criptomoedas` (híbrido) acharam a página (score real 0.4665), provando "busca por conceito encontra o que full-text não encontrava" contra infra 100% real.

**Calibração P2-2**: 7 queries reais (relevantes, cross-topic e gibberish) contra ~1600 chunks candidatos. Distribuição observada: p50=0.39, p90=0.485, máximo de ruído puro (query sem sentido) ≈0.52, correspondências diretas de tópico 0.63–0.74, correspondência conceitual parafraseada (o próprio gate) ≈0.47. `min-score` antigo (0.35) ficava abaixo da mediana — não filtrava quase nada. Subido para **0.45**: mantém o match conceitual e os diretos, corta a maior parte do ruído.

**Limitação conhecida, documentada em vez de forçada**: a página degenerada (listagem de links) pontua ~0.49–0.52 contra qualquer query, inclusive gibberish — nenhum valor de `min-score` separa isso de um match conceitual genuíno sem também perder o match genuíno, porque o "ruído" dessa página específica pontua mais alto que uma parafraseamento real. A correção de verdade é um filtro de qualidade de conteúdo antes de embedar (pular páginas que são majoritariamente listas de links/URLs), não um número — fica como item futuro, não fechado aqui.

### Gate final
Uma página nova é automaticamente resumida, classificada e (se necessário) traduzida dentro de alguns minutos após ser indexada, com o provedor de IA escolhido automaticamente e sem estourar nenhum limite gratuito sob uso normal.

**Status:** 🟢 validado contra Groq + Gemini + Ollama reais em 2026-09-06 (ver seção acima), e P2-1/P2-2 fechados no mesmo dia com Qdrant + Ollama reais (ver nota própria). Três bugs de correção achados e corrigidos nessas rodadas — todos invisíveis pros testes com fake/mock: margem de cota (Groq/Gemini nunca eram escolhidos), modelo Groq descontinuado, e uma página degenerada saturando o candidate pool da busca semântica. DoD da Fase 3 fechado; limitação de corpus (filtro de qualidade de conteúdo pré-embedding) registrada como item futuro, não bloqueante.

## 13. Prontidão para a Fase 4

Revisado ao fim da implementação da Fase 3:

- **`AIOrchestrator` disponível não bloqueia a Fase 4.** `extractEntities` foi deliberadamente deixado fora da interface (não é um `default` que lança) — a Fase 4 adiciona o método + um `EntityProcessor` sem tocar nos quatro processors existentes. O diff estrutural sumarizado entre versões reusa `summarize` como está.
- **Eventos in-process (ADR-009).** `ingestion` já é o publicador natural: `IngestionPipeline.process` roda numa transação e conhece `url`/`content_hash`/`version`. Adicionar `events.publishEvent(new PageIndexedEvent(...))` ao fim é aditivo — nenhum `ContentProcessor` precisa mudar. **Bloqueador P2-5 resolvido** (2026-09-06) — tabela `event_publication` agora é criada pelo próprio Modulith no boot.
- **`content_hash` como gate.** `AiEnrichmentGate` e `EmbeddingProcessor` já provam o padrão "conteúdo não mudou → não reprocessa". O motor de diff da Fase 4 usa o mesmo sinal para decidir quando re-extrair entidades.
- **Modulith.** `graph`/`intelligence` da Fase 4 entram como módulos novos reagindo a `PageIndexedEvent`; `ai` com `allowedDependencies = {}` continua isolado e reutilizável.
