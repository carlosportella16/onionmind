# OnionMind — Master Software Design Document (SDD)

*An AI-powered Knowledge Discovery Platform, initially focused on the Tor Network.*

**Versão:** 2.0 (consolidada)
**Data:** 2026-08-22
**Status:** Fase 0 concluída (verificado 2026-08-22: schema aplicado via Flyway, testes de integração reais com Testcontainers, fronteiras de módulo verificadas via `ApplicationModules.verify()`), iniciando Fase 1

Este documento consolida todas as decisões de arquitetura discutidas até aqui num único arquivo autocontido, pensado para ser usado como contexto (em `.claude`, ferramentas de IA, ou onboarding humano). Os documentos anteriores (`onionmind-sdd.md`, `onionmind-fase1-sdd.md`, `onionmind-descoberta-indexacao.md`) continuam existindo com código de implementação mais granular — este arquivo é a referência de mais alto nível que os une.

---

## 1. Visão geral

OnionMind descobre, coleta, indexa e analisa conteúdo de serviços `.onion`, transformando páginas não estruturadas em conhecimento pesquisável via IA. Stack: crawlers distribuídos em Go, monolito modular Java (Spring Boot + Spring Modulith), persistência poliglota (PostgreSQL, Qdrant, Neo4j, Redis), pipeline de IA multi-provider gratuito (Ollama, Groq, Gemini Flash).

Projetado para construção incremental por uma pessoa ou dupla. Cada fase entrega um produto funcional e demonstrável — nenhuma fase existe por completude arquitetural.

## 2. Personas

- **Pesquisador** — acompanha um tema ao longo do tempo. Prioriza diff/versionamento (Fase 4) e Research Agent (Fase 5).
- **Jornalista** — descobre mudanças sem revisitar páginas manualmente. Tolera mais latência em troca de profundidade.
- **Analista de segurança** — monitora grupos/tópicos, precisa de alerta quase em tempo real. No Cost Optimizer, latência pesa mais que custo.
- **Pesquisa acadêmica** — reconstrói histórico ao longo de meses. Depende mais do knowledge graph (Fase 4) que de tempo real.

## 3. User Journey

Um pesquisador busca "criptomoedas" (Fase 1: full-text). Encontra páginas com termos diferentes como "moeda digital" (Fase 2: semântica). Cada resultado já vem resumido e categorizado (Fase 3). Volta dias depois e pergunta "o que mudou" (Fase 4). Configura um Research Agent pra acompanhamento contínuo (Fase 5).

---

## 4. Architecture Decision Records

### ADR-001 — Monolito modular (Spring Modulith), não microsserviços

**Contexto:** 5 bounded contexts como serviços independentes custaria coordenação (deploy, tracing, contratos) que uma pessoa solo não deveria pagar antes de precisar.

**Decisão:** um artefato deployável, módulos (`ingestion`, `content`, `search`, `ai`, e futuramente `intelligence`, `graph`) com fronteiras impostas em build via Spring Modulith — import de classe interna de outro módulo sem passar pela API pública falha o build/teste.

**Critério de extração:** **(a)** módulo precisa escalar horizontalmente de forma independente, ou **(b)** ciclo de deploy de um módulo bloqueia o outro. Nenhum dos dois presente hoje.

### ADR-002 — Redpanda, não Kafka completo

**Decisão:** broker Kafka-compatible de binário único, sem ZooKeeper — cabe numa única VM ao lado de tudo mais. Protocolo idêntico ao Kafka; migrar para Kafka gerenciado depois é troca de broker, não reescrita.

### ADR-003 — Eventos JSON até a Fase 3, Avro + Schema Registry depois

**Contexto:** na Fase 1–2 existe um produtor (crawler) e um consumidor (`ingestion`). Schema Registry resolve evolução de schema entre múltiplos produtores/consumidores independentes — problema que só existe a partir da Fase 3.

**Decisão:** JSON puro nos eventos `raw-pages` até então.

### ADR-004 — `SourceConnector` genérico desde a Fase 1

**Contexto:** visão de longo prazo prevê RSS, GitHub, PDFs além de Tor (backlog, seção 5.7).

**Decisão:** crawler nomeado `TorConnector`, implementando interface genérica `SourceConnector`; evento carrega campo `source_type`. Preserva a opção sem construir nada agora.

### ADR-005 — `'simple'` como config de full-text search

**Contexto:** conteúdo `.onion` é fortemente multilíngue; stemming de um idioma específico distorceria ranking pra todo o resto.

**Decisão:** `to_tsvector('simple', ...)`. Relevância entre idiomas é resolvida pela busca semântica (Fase 2), não pelo full-text.

### ADR-006 — `AIOrchestrator` como interface própria + decorator chain interna

**Contexto:** acoplar processors direto ao Spring AI trava a troca de framework; cross-cutting concerns (cache, retry, métricas, validação) espalhados nos processors viram duplicação.

**Decisão:** interface `AIOrchestrator` própria (seção 6.2). Internamente, cadeia de decorators. Retry cobre falha transiente contra o mesmo provider; um loop de escalonamento separado troca de provider quando o problema é qualidade da resposta (confidence baixo, JSON inválido).

### ADR-007 — Monorepo, não um repositório por serviço

**Contexto:** o ADR-001 já rejeitou a fricção de coordenação entre serviços — dividir repositório é uma separação ainda mais forte que dividir serviço, e pagaria esse custo de novo sem o benefício de deploy independente.

**Decisão:** um repositório (`onionmind`), CI com workflows filtrados por `paths` (`ci-go.yml`, `ci-java.yml`) pra não rodar tudo a cada mudança isolada. `CODEOWNERS` cumpre o papel de "dono por área" sem precisar de permissão de repositório separada. Versionamento por tag de fase concluída (`v0.1` = Fase 1 fechada), não semver independente por componente.

**Critério de divisão futura:** **(a)** ritmo de release do crawler genuinamente desacoplado do core gerando atrito real; **(b)** um componente vira algo publicável pra fora do projeto (ex.: `TorConnector` como módulo Go open-source); **(c)** o time cresce a ponto de precisar controle de acesso por repositório, não só por diretório.

### ADR-008 — Java 25 + Spring Boot 4.1 + Spring Modulith 2.x + Spring AI 2.0 + Gradle Groovy DSL + Testcontainers 2.x

**Contexto:** Spring Boot 3.5 atingiu fim de suporte open-source em 30/06/2026 (release final 3.5.16). Spring Modulith 2.0 exige Boot 4 como baseline (com migração de schema de event publication — 3 colunas novas). Spring AI 2.0 (GA junho/2026) acompanha Boot 4; a linha 1.x fica presa ao Boot 3.5, já sem patch de segurança novo. Testcontainers 2.x renomeou artefatos/pacotes (`PostgreSQLContainer` mudou de pacote).

**Decisão:** já que o projeto ainda não tinha nada em produção, começar direto na geração atual em vez de construir sobre uma linha morta: Java 25 (LTS, suportado por Gradle ≥ 9.1.0 tanto pro daemon quanto via toolchain), Spring Boot 4.1.x, Spring Modulith 2.x, Spring AI 2.0 quando a Fase 3 chegar, Testcontainers 2.x.

**Gradle Groovy DSL, não Kotlin DSL:** grande parte do atrito reportado com Java 25 no ecossistema Gradle é específica do compilador Kotlin, que historicamente demora mais que o próprio Gradle pra suportar cada JDK novo (relatos de "Kotlin does not yet support 25 JDK target"). Como `core/` não usa Kotlin em lugar nenhum, esse atrito não se aplica.

**Toolchain, não `sourceCompatibility` direto:** `build.gradle` usa `java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }` — o daemon do Gradle pode continuar rodando em qualquer JDK já instalado, o toolchain provisiona o 25 só pra compilar. Evita depender de o daemon em si já suportar 25 (área historicamente mais instável que o suporte via toolchain).

### ADR-009 — Eventos in-process (Spring Modulith) para comunicação entre módulos, Kafka só entre processos

**Contexto:** a partir da Fase 4, mais de um módulo (`graph`, `intelligence`) precisa reagir ao mesmo fato ("uma página foi indexada") sem se conhecerem entre si. Resolver isso com chamada direta de método recria o acoplamento que o ADR-001 existe pra evitar; resolver com Kafka/Redpanda paga serialização, rede e a cerimônia do Schema Registry (ADR-003) por algo que nunca sai da JVM.

**Decisão:** dois mecanismos de evento coexistem, cada um resolvendo uma fronteira diferente — nunca o mesmo problema:

- **Redpanda** — cruza *processo* (`crawler` Go ↔ `core` Java). Único jeito de dois binários conversarem.
- **Spring Modulith (`ApplicationEventPublisher` + `@ApplicationModuleListener`)** — cruza *módulo* dentro do mesmo `core/`. Publisher não conhece os consumers; `@ApplicationModuleListener` (não `@EventListener` puro) roda assíncrono, numa transação própria, depois que a transação do publisher já commitou — nunca vê dado não commitado, e uma falha no listener não derruba a escrita original.

O Event Publication Registry (tabela no mesmo Postgres) grava cada publicação antes de disparar os listeners; se a aplicação cair no meio do caminho, a próxima subida reprocessa o que ficou pendente (`republish-outstanding-events-on-restart: true`) — durabilidade tipo Kafka, sem sair do banco que já existe.

```
PedidoService (ou IngestionPipeline)
     │
     │ publishEvent()
     ▼
Event Publication Registry (Postgres) ── grava "publicado, pendente"
     │
     ├──────────────► HandlerA   (assíncrono, transação própria)
     ├──────────────► HandlerB   (assíncrono, transação própria)
     └──────────────► HandlerC   (assíncrono, transação própria)
```

**Quando introduzir:** só quando o segundo consumidor independente aparecer — mesmo raciocínio do ADR-003 sobre não adicionar Schema Registry antes de existir mais de um consumidor. Hoje (Fase 1) `IngestionPipeline` chama `PageRepository` direto, e está certo assim: um consumidor só não justifica pub/sub. O evento ganha sentido na Fase 4, quando `graph` e `intelligence` aparecem ao mesmo tempo (seção 6.6).

**Escape hatch pra sair da JVM depois, sem reescrever nada:** anotar o evento com `@Externalized("topic::routingKey")` e adicionar `spring-modulith-events-kafka` (runtime) — o mesmo evento passa a ser consumido pelo `@ApplicationModuleListener` interno **e** publicado no Redpanda ao mesmo tempo. Como `core/build.gradle` já tem `modulith` e `kafka` juntos (o Initializr inclui essa dependência de externalização automaticamente quando detecta as duas), essa porta já está preparada sem trabalho extra.

**Housekeeping:** `EventPublication`s completadas precisam de purga periódica — a tabela cresce sem limite senão. `ApplicationModules.of(OnionMindApplication.class).verify()` entra no `ci-java.yml` como fitness function: falha o build se algum código acessar `.internal` de outro módulo ou criar dependência circular — é o que torna o ADR-001 verificável, não só documentado.

---

## 5. Roadmap por fases

| Fase | Escopo | Fora de escopo | Gate de saída |
|------|--------|-----------------|---------------|
| **0** — Fundação | Repo, CI, docker-compose, schema inicial c/ versionamento e confidence, interfaces no-op | — | Infra sobe, testes de integração passam |
| **1** — Crawler + Busca Textual (MVP real) | Go crawler (Tor, worker pool, circuit breaker, dedup), Redpanda, sanitização, Postgres full-text (`tsvector`/GIN), API REST, React SPA | Embeddings, Qdrant, Neo4j, qualquer LLM | Descobrir → indexar → buscar em minutos, repetível, sem intervenção manual |
| **2** — Busca Semântica | `EmbeddingProcessor` (Ollama/Cloudflare), Qdrant, fusão simples full-text + vetorial | Geração de texto via LLM | Busca por conceito encontra o que full-text não encontrava |
| **3** — IA Generativa + Cost Optimizer | `AIOrchestrator` real (Ollama/Groq/Gemini), Translation/Summarizer/Classifier processors, confidence score, filtro de conteúdo ilegal antes de qualquer LLM | — | Página nova resumida/classificada em minutos, sem estourar tier gratuito |
| **4** — Knowledge Graph + Versionamento | `EntityProcessor`, Neo4j, diff via `content_hash` + LLM, `PageIndexedEvent` in-process (ADR-009) coordenando `graph`/`intelligence` | — | "O que mudou desde ontem" responde correto e curto |
| **5** — RAG Completo + Agent Playground | Query rewriting, hybrid retrieval (RRF k=60), re-rank, Agent Playground (privado por usuário) | Marketplace público de agentes | Pipeline RAG funcional, extração de módulos avaliada com dados reais |
| **6 / backlog** — Conectores externos | RSS, GitHub, PDF, S3 — cada um só mais um publisher no mesmo tópico Redpanda | Tudo — é backlog, não fase comprometida | — |

---

## 6. Padrões de design centrais

### 6.1 `ContentProcessor` — pipeline de plugins

```java
public interface ContentProcessor {
    ProcessingResult process(Document document);
    boolean supports(DocumentType type);
    default int order() { return 0; }
}
```

`order()` tem default no código mas a ordem efetiva deve ser sobrescrevível por configuração — permite desligar/reordenar etapa sem recompilar. `ProcessingResult` carrega documento + status (SUCCESS/SKIPPED/FAILED) + erro, sem poluir o modelo `Document`.

| Fase | Processor | order |
|------|-----------|-------|
| 1 | `HtmlSanitizerProcessor` | 0 |
| 2 | `EmbeddingProcessor` | 100 |
| 3 | `LanguageDetectorProcessor`, `TranslationProcessor`, `SummarizerProcessor`, `ClassifierProcessor` | 10–40 |
| 4 | `EntityProcessor` | 50 |

### 6.2 `AIOrchestrator` — interface + decorator chain + loop de escalonamento

**Interface pública:**

```java
public interface AIOrchestrator {
    Summary summarize(String text, TaskContext ctx);
    Embedding embed(String text, TaskContext ctx);
    List<Entity> extractEntities(String text, TaskContext ctx);
    String translate(String text, String targetLang, TaskContext ctx);
}

public record TaskContext(
    TaskType type, int approxTokens, String sourceLanguage,
    boolean critical, boolean interactive,   // interactive: seção 9
    Double previousConfidence, int attemptNumber
) {}
```

**Fluxo interno** (dois mecanismos de resiliência distintos — não misturar):

```
Processor chama .summarize(text, ctx)
    │
    ▼
MetricsDecorator (latência/custo)
    │
    ▼
CacheDecorator ──► hit? retorna direto
    │ miss
    ▼
RetryDecorator (backoff, falha TRANSIENTE — mesmo provider)
    │
    ▼
CostOptimizerRouter ──► escolhe provider (seção 6.3)
    │
    ▼
Provider.complete()
    │
    ▼
ValidationDecorator ──► confidence ≥ 0.7 e JSON válido?
    │ NÃO — problema de QUALIDADE, não de rede
    └──► volta ao Router, PRÓXIMO provider da escada (attempt++)
    │ SIM
    ▼
retorna ao processor
```

`RetryDecorator` cobre timeout/rede contra o **mesmo** provider. O loop de escalonamento troca de provider quando a resposta em si é ruim — dois problemas diferentes, duas respostas diferentes.

### 6.3 AI Cost Optimizer

Regras simples na Fase 3, não ML:

- Texto curto (< 500 tokens), não crítico, **batch** → Ollama local primeiro
- Texto longo/crítico, ou **interativo** (seção 9) → Groq primeiro, Gemini Flash como segundo
- `confidence < 0.7` ou JSON inválido → próximo da escada
- Ollama é sempre o fallback final — nunca estoura quota

```java
List<AIProvider> ladderFor(TaskContext ctx) {
    if (ctx.interactive()) return List.of(groq, gemini, ollama); // nuvem primeiro, sempre
    return (ctx.approxTokens() < 500 && !ctx.critical())
        ? List.of(ollama, groq, gemini)
        : List.of(groq, gemini, ollama);
}
```

**Quota tracker (Redis)** — diário E por minuto (o teto por minuto estoura primeiro sob concorrência real, não o diário):

```java
boolean withinMinuteLimit(String providerId) {
    String key = "quota:%s:minute:%d".formatted(providerId, Instant.now().getEpochSecond() / 60);
    long used = redis.opsForValue().increment(key);
    redis.expire(key, Duration.ofSeconds(90));
    return used <= MINUTE_LIMITS.get(providerId);
}
```

### 6.4 Confidence Score

Todo campo de IA persistido carrega confiança junto (`JSONB`: `{"text": "...", "confidence": 0.91}`). Alimenta o Cost Optimizer, permite auditoria, vira métrica de qualidade ao longo do tempo. JSON malformado é tratado como confidence 0, reprocessado pelo mesmo `ValidationDecorator` — sem sistema de retry separado.

### 6.5 Versionamento e diff

`content_hash` (hash do conteúdo normalizado), `first_seen_at`, `last_seen_at`, `version`. Re-crawl com hash diferente incrementa versão e arquiva a anterior em `page_versions`. A partir da Fase 4, diff estrutural entre versões é sumarizado pelo LLM já disponível.

**O uso mais valioso do `content_hash` não é o diff da Fase 4** — é o gate que impede reprocessar por IA conteúdo que não mudou, economizando cota gratuita antes mesmo de chegar no Cost Optimizer:

```java
public ProcessingResult process(Document doc) {
    String currentHash = sha256(doc.extractedText());
    if (qdrant.hasUnchangedEmbedding(doc.url(), currentHash)) {
        return ProcessingResult.unchanged(doc); // pula IA inteiramente
    }
    // ... processa normalmente
}
```

### 6.6 Eventos in-process entre módulos (ADR-009)

`ingestion` publica; `graph` e `intelligence` (Fase 4) reagem, sem que `ingestion` conheça nenhum dos dois — a dependência aponta na direção contrária, o que é o que permite extrair qualquer módulo pra um serviço separado depois (critério de extração do ADR-001) sem tocar em `ingestion`.

```java
// ingestion/PageIndexedEvent.java — pacote público do módulo, não .internal
public record PageIndexedEvent(
    Long pageId, String url, String contentHash, int version, boolean isNewVersion
) {}

// ingestion/IngestionPipeline.java
@Component
public class IngestionPipeline {
    private final ApplicationEventPublisher events;

    @Transactional
    public void process(RawPageEvent event) {
        // ... pipeline de ContentProcessor ...
        var outcome = repository.upsertWithVersioning(doc);
        events.publishEvent(new PageIndexedEvent(
            outcome.pageId(), doc.url(), outcome.contentHash(), outcome.version(), outcome.isNewVersion()));
    }
}
```

```java
// graph/EntityExtractionListener.java (Fase 4)
@Component
class EntityExtractionListener {
    @ApplicationModuleListener
    void on(PageIndexedEvent event) {
        if (!event.isNewVersion()) return; // mesmo gate do content_hash — 6.5
        entityExtractionService.extractAndPersist(event.pageId());
    }
}

// intelligence/AlertCheckListener.java (Fase 4)
@Component
class AlertCheckListener {
    @ApplicationModuleListener
    void on(PageIndexedEvent event) {
        alertRuleEngine.evaluate(event.pageId());
    }
}
```

**Tabela de decisão — qual mecanismo usar:**

| | Chamada direta | Evento in-process | Kafka/Redpanda |
|---|---|---|---|
| Cruza processo | Não | Não | Sim |
| Acoplamento | Alto | Baixo | Baixo |
| Durabilidade | Mesma transação | Event Publication Registry (Postgres) | Log do broker |
| N réplicas do `core/` | — | Cada réplica só vê os próprios eventos | Consumer groups distribuem entre réplicas |
| Quando usar | 1 consumidor, mesmo módulo | Vários consumidores, mesma JVM | Cruza processo, ou precisa rebalancear entre réplicas |

**Antipadrões a evitar:** acessar repositório de outro módulo direto (`graph` lendo `PageRepository` de `ingestion`) — sempre pela API pública do módulo; `@EventListener` síncrono puro pra comunicação entre módulos — recria o acoplamento que o evento existe pra evitar, use `@ApplicationModuleListener`; deixar tudo no pacote raiz do projeto (`com.onionmind`) sem sub-pacote de módulo — vira "shared utils" e o Modulith não consegue verificar fronteira nenhuma.

---

## 7. Descoberta de páginas `.onion`

### 7.1 O ciclo contínuo

```
Seeds curados + submissão manual
         │
         ▼
   Frontier (fila) ◄────────────────┐
         │                          │
         ▼                          │
   Worker: TorConnector.Fetch()     │
         │                          │
         ▼                          │
   Extrai links + filtro anti-trap  │
         │                          │
         ▼                          │
   Sucesso → novos links na fila ───┘
   Falha → backoff exponencial (Redis) ───┘ (re-enfileira depois do backoff)
```

Nunca termina — cada iteração pode gerar mais trabalho do que consumiu.

### 7.2 Decisões chave

- **Anti-trap:** heurístico bloqueia parâmetro de sessão na URL e segmento de path repetido 3+ vezes no mesmo caminho, mais um teto absoluto por host (`max_pages_per_host`) como segunda linha de defesa.
- **`robots.txt`:** sinal de cortesia, não fronteira de segurança — sanitização continua obrigatória independente do que ele disser.
- **Liveness/backoff vive no Redis, não no Postgres** — o crawler nunca fala com o banco (seção 8.1); colocar estado de liveness numa tabela Postgres escrita pelo Go quebraria essa fronteira sem necessidade, já que o Redis já está disponível pro dedup. Backoff exponencial cap em 7 dias — nunca marca como morto permanente, serviços `.onion` ressuscitam.
- **Evolução futura do Frontier:** hoje é um `chan` em memória (uma instância só). Quando escalar pra múltiplos crawlers, migra pra Redis Sorted Set com score = timestamp do próximo attempt — unifica fila de descoberta e agendamento de backoff na mesma estrutura, `ZPOPMIN` sem duplicar trabalho entre instâncias.

---

## 8. Indexação

### 8.1 Evolução por fase

```
Texto extraído (pós-sanitização)
    │
    ├──────────────────┬──────────────────────────────┐
    ▼ (Fase 1)          ▼ (Fase 2, com gate de hash)   │
tsvector GENERATED   content_hash mudou?               │
    │                    │ sim                         │
    ▼                    ▼                              │
Índice GIN           Chunker (512 tok, ~15% overlap)     │
    │                    │                              │
    ▼                    ▼                              │
ts_rank               EmbeddingProcessor → Qdrant        │
(não é BM25 — 8.2)       │                              │
    │                    ▼                              │
    │              similaridade vetorial (cosine)        │
    │                    │                              │
    └──────────┬─────────┘                              │
               ▼                                        │
   Fase 5 — RRF fusion (k=60) + cross-encoder re-rank ◄──┘
```

### 8.2 Decisões chave

- **`search_vector` é coluna `GENERATED ALWAYS AS`** — recalcula em todo INSERT/UPDATE, sem lag de indexação separado (diferente de Elasticsearch com seu intervalo de refresh).
- **`ts_rank` não é BM25.** É ranking mais simples, sem normalização por tamanho de documento. Pra BM25 real na Fase 5 sem sair do Postgres: extensão `pg_search` (ParadeDB) antes de considerar um motor de busca dedicado.
- **Granularidade muda na Fase 2:** full-text opera no documento inteiro (match literal em qualquer parte já é útil); embeddings precisam de chunk, porque um documento longo gera um vetor "médio" que não representa nada bem. Qdrant guarda `page_id` + `chunk_index`; conteúdo real só vive no Postgres.
- **Consistência eventual observável:** se o embedding falhar depois do full-text já ter commitado, a página fica buscável por palavra-chave mas ausente da busca semântica até reprocessar — query de páginas "sem embedding correspondente" alimenta um job de backfill.
- **Quase-duplicatas (clones/phishing, comuns em Tor):** `content_hash` só pega idêntico byte a byte. SimHash (fingerprint 64-bit, distância de Hamming ≤ 3) fica registrado como lacuna conhecida pra um `NearDuplicateDetectorProcessor` futuro — não é trabalho de MVP.

---

## 9. Arquitetura de tempo de resposta

**Distinção central: busca não é o mesmo problema que chat.**

`/api/search` (Fase 1+) nunca chama LLM ao vivo — resumo/categoria já foram gerados na ingestão e vivem como JSONB na linha. Alvo: dezenas de milissegundos.

```java
// Fase 2+: full-text e vetorial em paralelo, não sequencial
var textFuture = CompletableFuture.supplyAsync(() -> fullTextSearch(q), virtualThreadExecutor);
var vectorFuture = CompletableFuture.supplyAsync(() -> qdrant.search(q), virtualThreadExecutor);
// latência = a mais lenta das duas, não a soma
```

`statement_timeout` de ~2s no datasource da API evita query patológica travando a request.

`/api/chat` (Fase 5) tem geração de LLM real — segundos, não milissegundos, por natureza do problema. `TaskContext.interactive()` (seção 6.3) garante que a escada de providers vá pra nuvem primeiro; Ollama local nunca é a primeira escolha aqui, porque 20-30s de espera numa VM pequena é experiência ruim mesmo sendo grátis.

**Degradação visível em vez de espera indefinida** se toda a escada estourar:

```java
CompletableFuture<ChatResponse> answer(String query) {
    var searchFuture = CompletableFuture.supplyAsync(() -> search(query)); // sempre rápido
    var ragFuture = CompletableFuture.supplyAsync(() -> orchestrator.summarize(buildRagPrompt(query), interactiveCtx))
        .orTimeout(12, TimeUnit.SECONDS);
    return searchFuture.thenCombine(ragFuture.handle((r, ex) -> ex == null ? r : null),
        (results, summary) -> new ChatResponse(results, summary, summary == null)); // degraded: true
}
```

Streaming via SSE (`text/event-stream`) reduz latência **percebida** mesmo sem reduzir a real — usuário lê enquanto o resto ainda gera.

---

## 10. Data design

```sql
CREATE TABLE pages (
    id              BIGSERIAL PRIMARY KEY,
    url             TEXT NOT NULL,
    source_type     TEXT NOT NULL DEFAULT 'tor',       -- ADR-004
    raw_html        TEXT,
    extracted_text  TEXT,
    content_hash    TEXT NOT NULL,                     -- versionamento (6.5)
    version         INT NOT NULL DEFAULT 1,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    summary         JSONB,   -- {"text": "...", "confidence": 0.91} — 6.4
    category        JSONB,
    language        JSONB,
    entities        JSONB,
    search_vector   tsvector GENERATED ALWAYS AS
                    (to_tsvector('simple', coalesce(extracted_text, ''))) STORED,  -- ADR-005, Fase 1
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_pages_url ON pages (url);
CREATE INDEX idx_pages_content_hash ON pages (content_hash);
CREATE INDEX idx_pages_search_vector ON pages USING GIN (search_vector);

CREATE TABLE page_versions (
    id BIGSERIAL PRIMARY KEY, page_id BIGINT NOT NULL REFERENCES pages(id),
    version INT NOT NULL, content_hash TEXT NOT NULL, extracted_text TEXT,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Evento `raw-pages` (JSON, ADR-003): `{"url", "source_type", "html", "fetched_at"}`.

Qdrant (Fase 2+): coleção `page_chunks`, payload `page_id` + `chunk_index`, fonte da verdade continua no Postgres.

Neo4j (Fase 4+): nós tipados (`:OnionService`, `:Person`, `:CryptoWallet`...), merge via business key.

---

## 11. Segurança por fase

| Fase | Medida |
|------|--------|
| 1 | HTML sanitizado (OWASP allowlist) antes de qualquer persistência; crawler nunca fala com Postgres, só publica no Redpanda; container non-root, capabilities dropped |
| 3+ | Filtro de conteúdo ilegal (hash perceptual) **antes** de qualquer LLM processar — sem atalho de MVP aqui |
| 4+ | Se volume justificar Kubernetes: NetworkPolicy default-deny, gVisor |
| 5+ | Configuração de agente compartilhável = conteúdo a moderar, não metadado inofensivo (uma combinação de keywords pode virar receita de busca pro que o filtro da Fase 3 existe pra bloquear); templates genéricos, não parâmetros customizados de usuário; Playground fica **privado por usuário**, marketplace público é backlog condicionado a existir moderação primeiro |

---

## 12. Estratégia de LLM gratuito

| Fase | Uso | Provedor |
|------|-----|----------|
| 1–2 | Embeddings apenas | Ollama local (`nomic-embed-text`) ou Cloudflare Workers AI |
| 3 | Resumo/classificação/tradução em batch | Ollama → Groq → Gemini Flash (escada não-interativa) |
| 5 | Chat interativo | Groq/Gemini Flash primeiro (escada interativa, seção 9); Ollama nunca é primeira opção aqui |

## 13. Hospedagem a custo zero

Oracle Cloud Always Free (VM Ampere A1) roda tudo via `docker-compose`, incluindo Ollama. PostgreSQL na mesma VM inicialmente; Qdrant Cloud free tier (1GB/4GB) quando quiser separar; Neo4j AuraDB Free (200K nós/400K relações) na Fase 4; Cloudflare Pages pro frontend estático.

---

## 14. Repositório e ferramental

**Estrutura** (ver ADR-007 e ADR-008):

```
onionmind/
├── docker-compose.yml, .gitignore, README.md
├── .github/{CODEOWNERS, workflows/ci-go.yml, workflows/ci-java.yml}
├── docs/                       # este arquivo + os 3 SDDs anteriores
├── db/migrations/V1__initial_schema.sql
├── crawler/                    # Go, SourceConnector/TorConnector
├── core/                       # Java, Spring Boot 4.1 + Modulith 2.x (gerado via Spring Initializr)
└── web/                        # React, a partir da Fase 1
```

**Geração do `core/` via Spring Initializr** (não escrito à mão) — garante o Gradle wrapper de graça:

```bash
curl "https://start.spring.io/starter.zip?type=gradle-project&language=java&bootVersion=4.1.1.RELEASE&javaVersion=25&groupId=com.onionmind&artifactId=core&name=core&packageName=com.onionmind&dependencies=web,data-jdbc,modulith,kafka,flyway,postgresql,testcontainers,actuator" -o core.zip
```

`packageName` fica só `com.onionmind` (não `com.onionmind.core`) — os pacotes `content`/`ai` do projeto vivem em `com.onionmind`, um nível acima de onde o autopreenchimento colocaria, e o component scan do Spring não os encontraria se deixado errado.

**IDE:** IntelliJ IDEA Ultimate cobre o monorepo inteiro numa janela — Java/Spring nativo, Go via plugin oficial (`Settings → Plugins → Go`, só compatível com Ultimate), JS/TS sem custo adicional desde a unificação do produto (dez/2025). VS Code só entraria se a licença fosse Community (sem suporte a Go nele).

---

## 15. Observabilidade

Fase 1: logs estruturados JSON (Logback/`slog`) + health check — resolve a maior parte do debug solo. Fase 3+: Micrometer → Prometheus, métrica mais importante é consumer lag do Redpanda. Histogramas separados pra busca (ms) e chat (s) — misturar as duas escalas esconde as duas.

---

## 16. Glossário

| Termo | Definição |
|-------|-----------|
| `ContentProcessor` | Plugin de etapa do pipeline de processamento |
| `AIOrchestrator` | Abstração sobre provedores de IA; processors nunca chamam Spring AI direto |
| `AIProvider` | Interface por adapter (Ollama/Groq/Gemini) |
| `CostOptimizerRouter` | Escolhe provider por tamanho, quota restante, criticidade e se é interativo |
| `SourceConnector` | Interface genérica de fonte de dados; `TorConnector` é a primeira implementação |
| `TaskContext` | Sinais de roteamento passados ao `AIOrchestrator` |
| `ValidationDecorator` | Verifica confidence/JSON; falha dispara escalonamento pra outro provider |
| Spring Modulith | Impõe fronteiras entre módulos dentro de um monolito Spring Boot |
| Redpanda | Broker Kafka-compatible de binário único |
| `PageIndexedEvent` | Evento in-process publicado por `ingestion`, consumido por `graph`/`intelligence` (Fase 4) — ADR-009 |
| `@ApplicationModuleListener` | Listener assíncrono e transacional do Spring Modulith; roda após o commit do publisher |
| Event Publication Registry | Tabela no Postgres que persiste publicações de evento in-process — garante at-least-once, sobrevive a restart |

---

## Ressalvas

- Limites de tier gratuito e versões de dependência mudam com frequência — os números aqui são de agosto de 2026; reconferir antes de qualquer decisão de produção.
- O roadmap assume desenvolvimento solo/dupla fora de horário integral.
- Extração de módulos, marketplace de agentes e Fase 6 ficam condicionados aos critérios explícitos em cada seção — não são compromissos antecipados.
