# OnionMind — Software Design Document (SDD)

*An AI-powered Knowledge Discovery Platform, initially focused on the Tor Network.*

**Versão:** 1.0  
**Data:** 2026-07-25  
**Status:** Em construção — Fase 0

---

## 1. Visão geral

OnionMind descobre, coleta, indexa e analisa conteúdo de serviços `.onion` da rede Tor, transformando páginas não estruturadas em conhecimento pesquisável via IA. A plataforma combina crawlers distribuídos em Go, um monolito modular Java (Spring Boot + Spring Modulith), persistência poliglota (PostgreSQL, Qdrant, Neo4j, Redis) e um pipeline de IA multi-provider gratuito (Ollama, Groq, Gemini Flash).

O sistema é projetado para ser construído incrementalmente por uma pessoa ou dupla. Cada fase entrega um produto funcional e demonstrável. Nenhuma fase existe por completude arquitetural — todas conectam a um degrau visível da user journey (seção 3).

## 2. Personas

Toda decisão de priorização dentro de cada fase — especialmente na Fase 5, onde caberia qualquer agente — deve responder: quem usa isso, e para quê?

**Pesquisador** — acompanha um tema ao longo do tempo, não faz busca pontual. Prioriza diff/versionamento (Fase 4) e Research Agent (Fase 5). Tolera mais latência em troca de profundidade.

**Jornalista** — quer descobrir mudanças relevantes sem revisitar centenas de páginas manualmente. Mesmas prioridades do pesquisador, com ênfase em resumos completos.

**Analista de segurança** — monitora grupos ou tópicos específicos e precisa ser avisado rápido. Prioriza alertas quase em tempo real; no Cost Optimizer, latência pesa mais que custo.

**Pesquisa acadêmica** — reconstrói o histórico de um tema ao longo de meses. Depende do knowledge graph (Fase 4) mais do que de alertas em tempo real.

## 3. User Journey

Um pesquisador digita "criptomoedas" na busca (Fase 1: já funciona por palavra-chave). O sistema encontra páginas relacionadas mesmo quando usam termos diferentes, como "moeda digital" ou "blockchain anônimo" (Fase 2: busca semântica). Cada resultado já vem com um resumo gerado automaticamente e uma categoria (Fase 3), então o pesquisador não precisa abrir cada página manualmente. Dias depois, ele volta e pergunta "o que mudou nesses fóruns desde a semana passada" e recebe um resumo das mudanças reais, não uma lista de páginas para revisitar uma por uma (Fase 4). Se o interesse dele for mais amplo — não uma busca pontual, mas acompanhamento contínuo de um tema — ele configura um Research Agent que monitora o assunto e manda um relatório periódico (Fase 5).

---

## 4. Decisões arquiteturais (ADRs)

### ADR-001: Monolito modular (Spring Modulith) em vez de microsserviços

**Contexto:** a arquitetura ideal teria cinco bounded contexts como serviços independentes (Ingestion, Content, Search, Intelligence, Graph). Para uma pessoa construindo solo, o custo operacional de cinco pipelines de deploy, tracing distribuído entre processos e versionamento de contrato entre serviços é maior que o benefício.

**Decisão:** um único artefato deployável organizado com Spring Modulith. Módulos (`ingestion`, `content`, `search`, `intelligence`, `graph`) com fronteiras impostas em build — importar classe interna de outro módulo sem passar pela API pública declarada falha o build/teste.

**Critério de extração:** **(a)** um módulo precisa escalar horizontalmente de forma independente, ou **(b)** o ciclo de deploy de um módulo está bloqueando outro. Até que um desses sinais apareça de verdade, tudo fica em um binário.

### ADR-002: Redpanda em vez de Kafka completo

**Contexto:** Kafka + ZooKeeper (ou KRaft) consome recursos e complexidade operacional significativos antes de qualquer lógica de negócio. Uma VM com 12GB de RAM precisa caber crawler + Java + banco + event bus.

**Decisão:** Redpanda — binário único, sem ZooKeeper, protocolo Kafka-compatible. Qualquer cliente Kafka (Java ou Go) funciona sem alteração. Migrar para Kafka gerenciado é troca de configuração de broker.

### ADR-003: JSON nos eventos até a Fase 3, Avro + Schema Registry depois

**Contexto:** na Fase 1 existe exatamente um produtor e um consumidor. Schema Registry resolve problemas de evolução de schema entre múltiplos produtores/consumidores independentes — problema que ainda não existe.

**Decisão:** eventos JSON na Fase 1–2. Migrar para Avro + Schema Registry quando a Fase 3 adicionar mais consumidores do mesmo tópico.

### ADR-004: `SourceConnector` genérico desde a Fase 1

**Contexto:** a visão de longo prazo prevê indexar RSS, GitHub, PDFs e outras fontes além de Tor. Preparar para isso não custa nada se feito na nomenclatura, e custa retrabalho de schema se ignorado.

**Decisão:** o crawler Go se chama `TorConnector`, implementando uma interface genérica `SourceConnector`. O schema do evento inclui um campo `source_type`. Isso não adianta trabalho de conectores futuros — só evita que "crawler" fique hardcoded como sinônimo de Tor.

### ADR-005: `'simple'` como config de full-text search (não idioma específico)

**Contexto:** conteúdo de rede Tor é fortemente multilíngue. Assumir um idioma na configuração de stemming distorce o ranking para todo o resto.

**Decisão:** `to_tsvector('simple', ...)` na Fase 1. Quem resolve relevância entre idiomas de verdade é a busca semântica (Fase 2), não o full-text.

### ADR-006: AIOrchestrator como interface própria com Decorator chain

**Contexto:** acoplar processors diretamente ao Spring AI impede trocar de framework de IA e dificulta adicionar cross-cutting concerns (cache, retry, métricas, validação).

**Decisão:** interface `AIOrchestrator` própria. Internamente implementada como cadeia de decorators: MetricsDecorator → CacheDecorator → RetryDecorator → CostOptimizerRouter → Provider → ValidationDecorator. Retry cobre falha transiente (rede/timeout) contra o mesmo provider; o loop externo de escalonamento troca de provider quando o problema é qualidade da resposta (confidence baixo ou JSON inválido).

---

## 5. Roadmap por fases

### Fase 0 — Fundação (semanas 1–2)

Ambiente de desenvolvimento pronto, nenhuma lógica de negócio ainda.

**Entregas:**

- Repositório monorepo com CI (lint + testes em Java e Go a cada push)
- `docker-compose.yml` local com Redpanda, Redpanda Console e PostgreSQL
- Schema inicial do Postgres com colunas de versionamento (`content_hash`, `first_seen_at`, `last_seen_at`, `version`) e campos JSONB de confidence (`summary`, `category`, `language`, `entities`) — nullable, vazios por semanas, sem custo agora, evitam migration dolorosa depois
- Interfaces `ContentProcessor` e `AIOrchestrator` com implementação no-op
- Flyway configurado (ou init script para dev)

**Gate de saída (teste automatizado):**

```java
@Testcontainers
class InfrastructureBootstrapTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withInitScript("001_schema.sql");
    @Container static RedpandaContainer redpanda = new RedpandaContainer("redpandadata/redpanda:v24.2.7");

    @Test void contextLoadsWithRealInfrastructure() { /* Spring sobe com infra real */ }
    @Test void schemaHasVersioningAndConfidenceColumns() { /* assert colunas existem */ }
}
```

**Estrutura de diretórios:**

```
onionmind/
├── docker-compose.yml
├── db/init/001_schema.sql
├── .github/workflows/ci.yml
├── crawler/                          # Go — esqueleto vazio, código real na Fase 1
│   ├── go.mod
│   ├── cmd/crawler/main.go
│   └── internal/
│       ├── connector/connector.go    # interface SourceConnector
│       ├── dedup/dedup.go
│       └── publisher/publisher.go
├── core/                             # monolito Java (Spring Modulith)
│   ├── build.gradle
│   └── src/main/java/com/onionmind/
│       ├── ingestion/                # módulo: consume eventos, sanitiza, persiste
│       ├── content/                  # módulo: ContentProcessor pipeline
│       │   ├── ContentProcessor.java
│       │   ├── ProcessingResult.java
│       │   └── NoOpContentProcessor.java
│       ├── search/                   # módulo: API de busca
│       └── ai/                       # módulo: AIOrchestrator
│           ├── AIOrchestrator.java
│           ├── TaskContext.java
│           └── NoOpAIOrchestrator.java
└── web/                              # React SPA — código real na Fase 1
```

---

### Fase 1 — Crawler + Busca Textual (semanas 2–6)

Provar a ponta a ponta: descoberta → coleta → indexação → busca, sem IA.

**Escopo:**

- Crawler Go com worker pool, SOCKS5h, circuit breaker (`gobreaker`), dedup por URL (Redis)
- Publicação no Redpanda (tópico `raw-pages`, JSON)
- Consumer Java sanitiza HTML (OWASP Java HTML Sanitizer), extrai texto, persiste com versionamento
- Full-text search nativo do PostgreSQL (`tsvector` + `tsquery` + índice GIN, config `'simple'`)
- API REST simples (`/api/search?q=...`), usando `websearch_to_tsquery` (aceita input natural)
- React SPA mínimo consumindo a API

**Explicitamente fora de escopo:** embeddings, Qdrant, Neo4j, qualquer chamada a LLM, tradução, sumarização.

**Componentes Go:**

```go
// --- SourceConnector interface (genérica) ---
type SourceConnector interface {
    ID() string
    Fetch(ctx context.Context, url string) (*RawPage, error)
}

type RawPage struct {
    URL        string    `json:"url"`
    SourceType string    `json:"source_type"`
    HTML       []byte    `json:"html"`
    FetchedAt  time.Time `json:"fetched_at"`
}

// --- TorConnector (implementação) ---
type TorConnector struct {
    client  *http.Client
    breaker *gobreaker.CircuitBreaker
}

func NewTorConnector(socksAddr string) (*TorConnector, error) {
    dialer, _ := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
    transport := &http.Transport{
        DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
            return dialer.Dial(network, addr) // socks5h: resolução no proxy, não local
        },
    }
    return &TorConnector{
        client: &http.Client{Transport: transport, Timeout: 45 * time.Second},
        breaker: gobreaker.NewCircuitBreaker(gobreaker.Settings{
            Name:        "tor-fetch",
            Timeout:     30 * time.Second,
            ReadyToTrip: func(c gobreaker.Counts) bool { return c.ConsecutiveFailures > 3 },
        }),
    }, nil
}

func (t *TorConnector) Fetch(ctx context.Context, url string) (*RawPage, error) {
    result, err := t.breaker.Execute(func() (interface{}, error) {
        req, _ := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
        resp, err := t.client.Do(req)
        if err != nil { return nil, err }
        defer resp.Body.Close()
        return io.ReadAll(io.LimitReader(resp.Body, 10<<20)) // 10MB cap
    })
    if err != nil { return nil, err }
    return &RawPage{URL: url, SourceType: "tor", HTML: result.([]byte), FetchedAt: time.Now()}, nil
}

// --- Worker pool ---
func RunWorkers(ctx context.Context, n int, jobs <-chan string, conn SourceConnector, dedup *Dedup, pub *Publisher) {
    var wg sync.WaitGroup
    for i := 0; i < n; i++ {
        wg.Add(1)
        go func() {
            defer wg.Done()
            for url := range jobs {
                canon := NormalizeURL(url)
                if dedup.SeenRecently(canon) { continue }
                page, err := conn.Fetch(ctx, url)
                if err != nil {
                    slog.Warn("fetch failed", "url", url, "err", err)
                    continue
                }
                dedup.MarkSeen(canon)
                pub.PublishRawPage(page)
            }
        }()
    }
    wg.Wait()
}

// --- URL normalization ---
func NormalizeURL(raw string) string {
    u, _ := url.Parse(strings.ToLower(strings.TrimSpace(raw)))
    u.Fragment = ""
    u.RawQuery = u.Query().Encode() // ordena parâmetros por chave
    if (u.Scheme == "http" && u.Port() == "80") || (u.Scheme == "https" && u.Port() == "443") {
        u.Host = u.Hostname()
    }
    return u.String()
}
```

**Dedup (Redis):** TTL de algumas horas — evita re-enfileirar a mesma URL num intervalo curto. É diferente do `content_hash` no Postgres, que decide se o conteúdo realmente mudou após uma nova coleta.

**Dockerfile do crawler (hardening mínimo):**

```dockerfile
FROM golang:1.23-alpine AS build
WORKDIR /src
COPY . .
RUN CGO_ENABLED=0 go build -o /crawler ./cmd/crawler

FROM gcr.io/distroless/static-debian12
COPY --from=build /crawler /crawler
USER nonroot:nonroot
ENTRYPOINT ["/crawler"]
```

**Componentes Java:**

```java
// --- HtmlSanitizerProcessor (único ContentProcessor real da Fase 1) ---
@Component
public class HtmlSanitizerProcessor implements ContentProcessor {
    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
        .allowElements("p", "a", "b", "i", "ul", "ol", "li", "h1", "h2", "h3")
        .allowUrlProtocols("http", "https")
        .allowAttributes("href").onElements("a")
        .requireRelNofollowOnLinks()
        .toFactory();

    public boolean supports(DocumentType type) { return type == DocumentType.HTML; }
    public int order() { return 0; }

    public ProcessingResult process(Document doc) {
        String clean = POLICY.sanitize(doc.rawHtml());
        String text = Jsoup.parse(clean).text();
        return ProcessingResult.success(doc.withExtractedText(text));
    }
}

// --- PageRepository (versionamento via content_hash) ---
@Repository
public class PageRepository {
    public void upsertWithVersioning(Document doc) {
        String hash = Hashing.sha256(doc.extractedText());
        var existing = findByUrl(doc.url());

        if (existing.isEmpty()) {
            insert(doc, hash, 1);
        } else if (!existing.get().contentHash().equals(hash)) {
            archiveCurrentVersion(existing.get());  // grava em page_versions
            updateWithNewVersion(doc, hash, existing.get().version() + 1);
        } else {
            touchLastSeenAt(existing.get().id());   // conteúdo igual, só atualiza timestamp
        }
    }
}

// --- SearchController ---
@GetMapping("/api/search")
public List<SearchResult> search(@RequestParam String q,
                                  @RequestParam(defaultValue = "0") int page) {
    return jdbc.query("""
        SELECT id, url, extracted_text,
               ts_rank(search_vector, websearch_to_tsquery('simple', ?)) AS rank
        FROM pages
        WHERE search_vector @@ websearch_to_tsquery('simple', ?)
        ORDER BY rank DESC LIMIT 20 OFFSET ?
        """, searchResultMapper(), q, q, page * 20);
}
```

**Migration (Flyway) para full-text:**

```sql
-- V2__fulltext_search.sql
ALTER TABLE pages
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(extracted_text, ''))) STORED;

CREATE INDEX idx_pages_search_vector ON pages USING GIN (search_vector);
```

**Gate de saída (teste automatizado):**

```java
@Test
void discoveredPageBecomesSearchableWithinPipeline() {
    publisher.publish(new RawPageEvent("http://test3xyz.onion", "tor", FIXTURE_HTML, Instant.now()));

    await().atMost(Duration.ofSeconds(10)).until(() ->
        jdbc.queryForObject("SELECT count(*) FROM pages WHERE url = ?", Integer.class,
                             "http://test3xyz.onion") == 1);

    var results = searchController.search("bitcoin", 0);
    assertThat(results).extracting(SearchResult::url).contains("http://test3xyz.onion");
}
```

---

### Fase 2 — Busca Semântica (semanas 6–9)

Sair de "contém a palavra X" para "é sobre o assunto X".

**Escopo:**

- `EmbeddingProcessor` — primeira implementação real de `ContentProcessor` com IA (apenas embeddings, não geração de texto)
- Modelo local via Ollama (`nomic-embed-text`, ~274MB, roda em CPU) ou Cloudflare Workers AI (modelos BGE, tier gratuito)
- Qdrant (self-hospedado na mesma VM, ou Qdrant Cloud free tier — 1GB RAM / 4GB disco)
- Busca passa a combinar full-text (Fase 1) + similaridade vetorial com fusão simples

**Ainda fora de escopo:** qualquer geração de texto via LLM. Fase 2 é representação, não geração.

**Gate de saída:** uma busca por um conceito (não uma palavra exata) retorna resultados relevantes que a busca textual da Fase 1 não encontrava.

---

### Fase 3 — IA Generativa + Cost Optimizer (semanas 9–13)

Adicionar entendimento gerado por LLM sem acoplar o sistema a um provedor específico nem a uma conta paga.

**Escopo:**

- Implementação real do `AIOrchestrator` com três adapters: Ollama, Groq, Gemini Flash
- AI Cost Optimizer (regras simples, não ML — seção 7.3)
- Processors: `TranslationProcessor`, `SummarizerProcessor`, `ClassifierProcessor`
- Confidence score em cada campo gerado (seção 7.4)
- Filtro de conteúdo ilegal (hashing perceptual) **antes** de qualquer processamento por LLM

**Gate de saída:** uma página nova é automaticamente resumida, classificada e traduzida em minutos, com o provedor de IA escolhido automaticamente e sem estourar nenhum limite gratuito.

---

### Fase 4 — Knowledge Graph + Versionamento (semanas 13–17)

Relacionar entidades entre si e responder "o que mudou".

**Escopo:**

- `EntityProcessor` (spaCy/GLiNER → escalona para LLM em casos ambíguos)
- Neo4j — AuraDB Free (200K nós / 400K relações)
- Motor de diff usa `content_hash` (Fase 0) + LLM (Fase 3) para gerar resumo natural do que mudou

**Gate de saída:** "o que mudou nesta categoria desde ontem" retorna resposta correta e curta.

---

### Fase 5 — RAG Completo + Agent Playground (semanas 17+)

Chat conversacional sobre toda a base, agentes especializados, relatórios automáticos.

**Escopo:**

- Pipeline RAG completo: query rewriting → hybrid retrieval (BM25 + dense, RRF k=60) → cross-encoder re-ranking (`ms-marco-MiniLM-L-6-v2`) → geração via LLM
- Agent Playground (privado por usuário): cada agente é composição de processors + prompt + perfil de custo
- Reavaliação de extração de módulos do monolito (critério da ADR-001)
- Configurações de agente mantidas privadas — compartilhamento público é backlog futuro com ressalva de moderação (seção 9)

---

## 6. Data design

### 6.1 PostgreSQL — schema principal

```sql
CREATE TABLE pages (
    id              BIGSERIAL PRIMARY KEY,
    url             TEXT NOT NULL,
    source_type     TEXT NOT NULL DEFAULT 'tor',

    raw_html        TEXT,
    extracted_text  TEXT,

    -- versionamento
    content_hash    TEXT NOT NULL,
    version         INT NOT NULL DEFAULT 1,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- saída de IA (Fase 3+)
    summary         JSONB,   -- {"text": "...", "confidence": 0.91}
    category        JSONB,   -- {"value": "marketplace", "confidence": 0.88}
    language        JSONB,   -- {"value": "pt", "confidence": 1.0}
    entities        JSONB,   -- [{"name": "...", "type": "CRYPTO_WALLET", "confidence": 0.85}]

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
```

**Notas:** colunas JSONB (não JSON) — indexáveis e queryable. `search_vector` (tsvector + GIN) adicionado via migration na Fase 1. Colunas de IA existem desde a Fase 0 mas ficam null até a Fase 3.

### 6.2 Evento Redpanda — `raw-pages`

```json
{
  "url": "http://example.onion/page",
  "source_type": "tor",
  "html": "<base64 ou texto>",
  "fetched_at": "2026-07-25T10:30:00Z"
}
```

JSON puro na Fase 1–2. Migrar para Avro + Schema Registry na Fase 3 (ADR-003).

### 6.3 Qdrant (Fase 2+)

Coleção `page_chunks`: cada documento é dividido em chunks de ~512 tokens com ~15% overlap. Cada vetor armazena `page_id` e `chunk_index` como payload — a fonte da verdade do conteúdo é o PostgreSQL.

### 6.4 Neo4j (Fase 4+)

Nós tipados: `:OnionService`, `:Person`, `:Organization`, `:CryptoWallet`, `:Technology`, `:Location`. Relações: `(:OnionService)-[:MENTIONS]->(:Entity)`, `(:Entity)-[:CO_OCCURS_WITH]->(:Entity)`. Merge via business keys (`MERGE (w:CryptoWallet {address: $addr})`).

---

## 7. Padrões de design centrais

### 7.1 ContentProcessor — pipeline de plugins

```java
public interface ContentProcessor {
    ProcessingResult process(Document document);
    boolean supports(DocumentType type);
    default int order() { return 0; }
}

public record ProcessingResult(
    Document document,
    Status status,       // SUCCESS, SKIPPED, FAILED
    Duration elapsed,
    String error
) {
    public static ProcessingResult success(Document doc) { /* ... */ }
    public static ProcessingResult unchanged(Document doc) { /* ... */ }
    public static ProcessingResult failed(Document doc, String error) { /* ... */ }
}
```

`order()` define prioridade default no código, mas a **ordem efetiva do pipeline** é sobrescrevível por configuração (YAML/properties) — sem isso, não é possível desligar ou reordenar uma etapa sem recompilar.

**Implementações por fase:**

| Fase | Processor | order |
|------|-----------|-------|
| 1 | `HtmlSanitizerProcessor` | 0 |
| 2 | `EmbeddingProcessor` | 100 |
| 3 | `LanguageDetectorProcessor` | 10 |
| 3 | `TranslationProcessor` | 20 |
| 3 | `SummarizerProcessor` | 30 |
| 3 | `ClassifierProcessor` | 40 |
| 4 | `EntityProcessor` | 50 |

### 7.2 AIOrchestrator — interface e decorator chain

**Interface pública (o que os processors enxergam):**

```java
public interface AIOrchestrator {
    Summary summarize(String text, TaskContext ctx);
    Embedding embed(String text, TaskContext ctx);
    List<Entity> extractEntities(String text, TaskContext ctx);
    String translate(String text, String targetLang, TaskContext ctx);
}

public record TaskContext(
    TaskType type,              // SUMMARIZE, CLASSIFY, TRANSLATE, EXTRACT_ENTITIES, EMBED
    int approxTokens,
    String sourceLanguage,
    boolean critical,           // ex.: classificação de conteúdo ilegal
    Double previousConfidence,  // null na primeira tentativa
    int attemptNumber
) {
    public TaskContext withAttempt(int n) { return new TaskContext(type, approxTokens, sourceLanguage, critical, previousConfidence, n); }
    public TaskContext withPreviousConfidence(double c) { return new TaskContext(type, approxTokens, sourceLanguage, critical, c, attemptNumber); }
}
```

**AIProvider (o que cada adapter implementa):**

```java
public interface AIProvider {
    String id();                                  // "ollama" | "groq" | "gemini"
    CompletionResponse complete(CompletionRequest request);
    ProviderQuota currentQuota();
}
```

**Adapters:**

```java
// --- Ollama (local, grátis, sem rate limit) ---
@Component
class OllamaProvider implements AIProvider {
    private final RestClient client; // http://localhost:11434
    public String id() { return "ollama"; }
    public CompletionResponse complete(CompletionRequest req) {
        var body = Map.of("model", "llama3.1:8b", "prompt", req.prompt(), "stream", false);
        var raw = client.post().uri("/api/generate").body(body).retrieve().body(OllamaResponse.class);
        return CompletionResponse.of(raw.response());
    }
    public ProviderQuota currentQuota() { return ProviderQuota.UNLIMITED; }
}

// --- Groq (grátis, latência baixa, teto diário) ---
@Component
class GroqProvider implements AIProvider {
    private final RestClient client; // https://api.groq.com/openai/v1 (OpenAI-compatible)
    public String id() { return "groq"; }
    public CompletionResponse complete(CompletionRequest req) {
        var body = new ChatCompletionRequest("llama-3.3-70b-versatile", req.toMessages());
        var raw = client.post().uri("/chat/completions").body(body).retrieve().body(ChatCompletionResponse.class);
        return CompletionResponse.of(raw.firstChoiceContent());
    }
    public ProviderQuota currentQuota() { return quotaTracker.remaining("groq"); }
}

// --- Gemini Flash (grátis, contexto 1M tokens) ---
@Component
class GeminiProvider implements AIProvider {
    private final RestClient client; // https://generativelanguage.googleapis.com/v1beta
    public String id() { return "gemini"; }
    public CompletionResponse complete(CompletionRequest req) {
        var body = new GenerateContentRequest(req.toGeminiContents());
        var raw = client.post().uri("/models/gemini-2.5-flash:generateContent").body(body)
                         .retrieve().body(GenerateContentResponse.class);
        return CompletionResponse.of(raw.candidateText());
    }
    public ProviderQuota currentQuota() { return quotaTracker.remaining("gemini"); }
}
```

**Decorator chain (implementação interna):**

```
Processor chama .summarize(text, ctx)
    → MetricsDecorator (registra latência/custo)
        → CacheDecorator (hit? retorna direto)
            → RetryDecorator (backoff, falha transiente contra mesmo provider)
                → CostOptimizerRouter → Provider.complete()
    ← ValidationDecorator (confidence ≥ 0.7 e JSON válido?)
        └─ NÃO → volta ao Router pedindo o PRÓXIMO provider da escada (loop de escalonamento)
```

**Loop de escalonamento (retry ≠ escalação):**

```java
CompletionResponse execute(CompletionRequest req, TaskContext ctx) {
    return metrics.record(ctx.type(), () -> {
        for (int attempt = 0; attempt <= MAX_ESCALATIONS; attempt++) {
            var provider = router.select(ctx.withAttempt(attempt));
            var cacheKey = cache.keyFor(req, provider.id());
            var cached = cache.get(cacheKey);
            if (cached != null) return cached;

            var raw = retry.execute(() -> provider.complete(req));
            quotaTracker.recordUsage(provider.id());

            var validated = validator.check(raw, ctx.type());
            if (validated.confidence() >= MIN_CONFIDENCE || attempt == MAX_ESCALATIONS) {
                cache.put(cacheKey, validated);
                return validated;
            }
            ctx = ctx.withPreviousConfidence(validated.confidence());
        }
        throw new AllProvidersExhaustedException();
    });
}
```

`retry.execute(...)` cobre falha de rede/timeout contra o mesmo provider. O `for` externo troca de provider quando o problema é qualidade da resposta. São dois mecanismos distintos.

### 7.3 AI Cost Optimizer — roteamento por custo e complexidade

Regras simples na Fase 3 (não ML):

- Texto curto (< 500 tokens), classificação simples → **Ollama** (local, grátis, sem fila)
- Texto médio/longo, resumo, tradução → **Groq** primeiro; se esgotou cota → **Gemini Flash**
- Confidence < 0.7 ou JSON inválido → reprocessa com o próximo da escada (ValidationDecorator)
- Ollama é sempre o fallback final — nunca estoura quota

**Quota tracker (Redis):**

```java
@Component
class ProviderQuotaTracker {
    private static final Map<String, Long> DAILY_LIMITS = Map.of(
        "groq", 14_400L, "gemini", 1_500L
    );

    ProviderQuota remaining(String providerId) {
        String key = "quota:%s:%s".formatted(providerId, LocalDate.now());
        long used = Optional.ofNullable(redis.opsForValue().get(key))
                             .map(Long::parseLong).orElse(0L);
        return new ProviderQuota(DAILY_LIMITS.get(providerId) - used, DAILY_LIMITS.get(providerId));
    }

    void recordUsage(String providerId) {
        String key = "quota:%s:%s".formatted(providerId, LocalDate.now());
        redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofHours(26));
    }
}
```

**Router:**

```java
@Component
class CostOptimizerRouter {
    List<AIProvider> ladderFor(TaskContext ctx) {
        return (ctx.approxTokens() < 500 && !ctx.critical())
            ? List.of(ollama, groq, gemini)
            : List.of(groq, gemini, ollama);
    }

    AIProvider select(TaskContext ctx) {
        return ladderFor(ctx).stream()
            .filter(p -> p.currentQuota().remaining() > SAFETY_MARGIN)
            .findFirst()
            .orElse(ollama);
    }
}
```

### 7.4 Confidence score

Todo campo gerado por IA e persistido carrega um valor de confiança junto do valor (`JSONB`, ex.: `{"text": "...", "confidence": 0.91}`). Propósitos: alimenta o Cost Optimizer (reprocessar se baixo); permite auditoria (mostrar ao usuário); vira métrica de qualidade do pipeline. Falhas de validação estrutural (JSON malformado) são tratadas como confidence 0 e reprocessadas pelo mesmo mecanismo — sem sistema de retry separado.

### 7.5 Versionamento e diff

Cada documento carrega `content_hash` (hash do conteúdo normalizado, sem boilerplate), `first_seen_at`, `last_seen_at` e `version`. Quando um re-crawl encontra hash diferente, incrementa a versão e guarda o conteúdo anterior em `page_versions`. A partir da Fase 4, o diff estrutural entre versões é sumarizado pelo LLM já disponível da Fase 3.

---

## 8. Segurança por fase

**Fase 1 (mínimo viável):** HTML sanitizado antes de qualquer persistência (OWASP Java HTML Sanitizer, allowlist); crawler publica no Redpanda, nunca fala com Postgres; container non-root, capabilities dropped, filesystem read-only; egress limitado ao proxy Tor e ao broker.

**Fase 3+ (IA processa conteúdo bruto):** filtro de conteúdo ilegal (hashing perceptual contra bases conhecidas) roda antes de qualquer processamento por LLM. Política de descarte automático com metadados mínimos para auditoria. Sem atalho de MVP: isso precisa existir antes de IA em produção pública.

**Fase 4+ (isolamento mais rigoroso):** se o volume justificar Kubernetes, aplicar NetworkPolicy default-deny e sandboxing (gVisor).

**Fase 5+ (configurações de agente):** se o Agent Playground permitir compartilhamento, tratar a configuração como conteúdo a ser moderado — combinações de palavras-chave e filtros podem funcionar como receita de busca para categorias que o filtro da Fase 3 existe para bloquear. Compartilhar apenas templates genéricos até existir moderação para configurações públicas.

---

## 9. Estratégia de LLM gratuito por fase

| Fase | Uso | Provedor | Observação |
|------|-----|----------|------------|
| 1–2 | Embeddings apenas | Ollama local (`nomic-embed-text`) ou Cloudflare Workers AI | Sem geração de texto |
| 3 | Resumo, classificação, tradução (batch) | Ollama (padrão) → Groq (qualidade) → Gemini Flash (volume) | Cost Optimizer roteia |
| 5 | Chat interativo (RAG) | Gemini Flash (1.500 req/dia, 1M ctx) + Groq (baixa latência) | Tráfego do usuário final |

Limites de tier gratuito mudam com frequência — números de julho de 2026, reconferir antes de produção.

---

## 10. Hospedagem a custo zero

**Computação:** Oracle Cloud Always Free (VM Ampere A1, 2 OCPUs / 12GB RAM) roda todo o docker-compose incluindo Ollama. Ressalva: disponibilidade regional varia; Oracle reclama instâncias ociosas (manter cron de atividade). Fallback: Hetzner CX22 (poucos euros/mês).

**PostgreSQL:** na mesma VM (sem teto de storage, sem latência extra). Para backups gerenciados: Neon (0.5GB/branch) ou Supabase (500MB) como free tiers permanentes.

**Qdrant:** self-hospedado na Fase 2; Qdrant Cloud free tier (1GB RAM / 4GB disco) quando quiser separar.

**Neo4j:** AuraDB Free (200K nós / 400K relações) na Fase 4. Self-hosted Community Edition se ultrapassar.

**Frontend:** Cloudflare Pages (estático, gratuito).

---

## 11. Observabilidade mínima viável

**Fase 1:** logs estruturados em JSON (`Logback` no Java, `slog` no Go) + health check endpoint. Um `correlation_id` por documento processado já dá a maior parte do valor de um tracing distribuído.

**Fase 3+:** Micrometer → Prometheus. Métricas críticas: consumer lag do Redpanda, latência P99 da API de busca, taxa de erro dos crawlers, volume do DLQ, uso de quota por provider. Dashboard Grafana simples.

**Fase 5+ (microsserviços de fato):** OpenTelemetry com propagação via headers do Kafka. Não antes.

---

## 12. Backlog: Fase 6 — conectores externos

A visão de longo prazo — indexar RSS, GitHub, PDFs e outras fontes além de Tor — já está preservada de graça pelo design orientado a eventos: todo conector publica no mesmo tópico Redpanda antes de qualquer processamento. Um conector novo é só mais um publisher no mesmo schema.

O ajuste já feito (Fase 1): crawler nomeado como `TorConnector` implementando `SourceConnector`, campo `source_type` no evento. Nenhum trabalho antecipado além disso — backlog explícito, não compromisso de entrega.

---

## 13. Glossário

| Termo | Definição |
|-------|-----------|
| `ContentProcessor` | Interface de plugin do pipeline de processamento — cada etapa (sanitização, tradução, embedding, etc.) implementa essa interface |
| `AIOrchestrator` | Abstração sobre provedores de IA — processors nunca chamam Spring AI, Ollama ou Groq diretamente |
| `AIProvider` | Interface que cada adapter de provedor implementa (Ollama, Groq, Gemini) |
| `CostOptimizerRouter` | Componente que escolhe qual provider usar com base em tamanho do texto, quota restante e criticidade |
| `SourceConnector` | Interface genérica para fontes de dados — `TorConnector` é a primeira implementação |
| `ProcessingResult` | Resultado de um processor: documento + status + métricas de execução |
| `TaskContext` | Contexto passado ao AIOrchestrator com sinais para roteamento (tokens, idioma, criticidade, tentativa) |
| `ValidationDecorator` | Camada que verifica confidence e integridade do JSON; falha dispara escalonamento para outro provider |
| Spring Modulith | Framework que impõe fronteiras entre módulos dentro de um monolito Spring Boot |
| Redpanda | Broker Kafka-compatible de binário único, sem ZooKeeper |

---

## 14. Definition of Done por fase

### Fase 0
- [ ] `docker-compose up` sobe Postgres + Redpanda + Redpanda Console
- [ ] Schema `001_schema.sql` cria `pages` + `page_versions` com versionamento e confidence
- [ ] CI roda lint + testes em Java e Go a cada push
- [ ] `ContentProcessor` e `AIOrchestrator` compilam com no-op registrado no Spring context
- [ ] `InfrastructureBootstrapTest` (Testcontainers) passa no CI

### Fase 1
- [ ] Crawler Go descobre e coleta páginas `.onion` via SOCKS5h
- [ ] Eventos `raw-pages` publicados no Redpanda
- [ ] Consumer Java sanitiza HTML e persiste com versionamento em PostgreSQL
- [ ] Full-text search funcional via `websearch_to_tsquery`
- [ ] React SPA exibe resultados de busca
- [ ] Teste end-to-end (Testcontainers): evento → persistência → busca retorna resultado

### Fase 2
- [ ] `EmbeddingProcessor` gera vetores via Ollama/Cloudflare
- [ ] Vetores indexados no Qdrant
- [ ] Busca combina full-text + vetorial
- [ ] Busca por conceito retorna resultados que busca textual não encontrava

### Fase 3
- [ ] `AIOrchestrator` funcional com 3 adapters (Ollama, Groq, Gemini)
- [ ] Cost Optimizer roteia automaticamente entre providers
- [ ] Páginas auto-resumidas, classificadas e traduzidas com confidence
- [ ] Filtro de conteúdo ilegal ativo antes de qualquer processamento LLM
- [ ] Nenhum tier gratuito estourado sob uso normal

### Fase 4
- [ ] `EntityProcessor` extrai entidades e popula Neo4j
- [ ] Motor de diff detecta mudanças reais via `content_hash`
- [ ] Query "o que mudou desde ontem" retorna resumo correto

### Fase 5
- [ ] Pipeline RAG completo: rewrite → hybrid retrieval → re-rank → geração
- [ ] Agent Playground funcional (privado por usuário)
- [ ] Avaliação documentada de quais módulos justificam extração
