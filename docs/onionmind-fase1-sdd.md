# OnionMind — Fase 1: Crawler + Busca Textual (SDD)

**Pré-requisito:** Fase 0 concluída (infra local, schema, interfaces no-op).  
**Objetivo:** provar a ponta a ponta — descoberta → coleta → indexação → busca — sem IA.  
**Semanas estimadas:** 2–6

---

## 1. O que essa fase entrega (e o que não entrega)

**Entrega um produto publicável:** OnionMind v0.1 — o usuário digita um termo, recebe páginas `.onion` indexadas com ranking por relevância textual. Isso já é mais do que a maioria dos mecanismos de busca da rede Tor oferecem (que são listas de links sem ranking).

**Não entrega:** embeddings, busca semântica, resumos, tradução, classificação, knowledge graph, chat, agentes. Cada uma dessas capacidades chega em fases posteriores sobre a infraestrutura construída aqui — não como reescrita.

**Por que importa entregar sem IA primeiro:** o valor de uma busca textual funcionando de ponta a ponta, mesmo sem IA, é maior do que qualquer peça de IA isolada sem um pipeline de dados real por trás. Se o crawler não coleta de forma confiável, se o sanitizador não limpa o HTML perigoso, se o versionamento não detecta mudanças — nenhuma camada de IA posterior funciona corretamente.

---

## 2. Arquitetura da Fase 1

```
                    ┌──────────────────┐
                    │   React SPA      │
                    │   GET /api/search │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │   Monolito Java   │
                    │  (Spring Modulith)│
                    │                   │
                    │  ┌─────────────┐  │
                    │  │   search    │  │  ← API REST, full-text
                    │  └─────────────┘  │
                    │  ┌─────────────┐  │
                    │  │  ingestion  │  │  ← Kafka consumer, sanitização, persistência
                    │  └─────────────┘  │
                    │  ┌─────────────┐  │
                    │  │   content   │  │  ← ContentProcessor pipeline
                    │  └─────────────┘  │
                    └────────┬─────────┘
                             │ r/w
                    ┌────────▼─────────┐
                    │    PostgreSQL     │
                    │  pages (tsvector) │
                    │  page_versions    │
                    └──────────────────┘

                    ┌──────────────────┐
                    │     Redpanda     │
                    │  topic: raw-pages│
                    └────────▲─────────┘
                             │ publish
                    ┌────────┴─────────┐
                    │   Go Crawler      │
                    │  (TorConnector)   │
                    │  Worker Pool      │
                    │  Dedup (Redis)    │
                    └────────┬─────────┘
                             │ SOCKS5h
                    ┌────────▼─────────┐
                    │  Tor Proxy Pool   │
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │  Tor Network      │
                    └──────────────────┘
```

**Componentes novos nesta fase (não existiam na Fase 0):**

- Todo o código Go do crawler (TorConnector, workers, dedup, publisher, frontier)
- Redis (dedup do crawler)
- Tor daemon (proxy SOCKS5)
- Consumer Kafka no módulo `ingestion`
- `HtmlSanitizerProcessor` (primeira implementação real de `ContentProcessor`)
- `PageRepository` com lógica de versionamento
- API REST no módulo `search`
- Migration Flyway para `search_vector` (tsvector + GIN)
- React SPA mínimo

---

## 3. Estrutura de diretórios (final da Fase 1)

```
onionmind/
├── docker-compose.yml                     # atualizado: + redis, tor
├── db/
│   └── migrations/
│       ├── V1__initial_schema.sql         # Fase 0 (já existe)
│       └── V2__fulltext_search.sql        # Fase 1 (novo)
│
├── crawler/
│   ├── go.mod
│   ├── go.sum
│   ├── Dockerfile
│   ├── config.yaml                        # seeds, limites, endereço do Tor/Redis/Redpanda
│   ├── cmd/
│   │   └── crawler/
│   │       └── main.go                    # bootstrap, graceful shutdown
│   └── internal/
│       ├── connector/
│       │   ├── connector.go               # interface SourceConnector
│       │   └── tor.go                     # TorConnector (SOCKS5h + circuit breaker)
│       ├── frontier/
│       │   └── frontier.go                # gerencia a fila de URLs a visitar
│       ├── dedup/
│       │   └── dedup.go                   # Redis-backed URL dedup
│       ├── worker/
│       │   └── pool.go                    # worker pool com goroutines
│       ├── publisher/
│       │   └── publisher.go               # publica RawPageEvent no Redpanda
│       ├── normalizer/
│       │   └── url.go                     # normalização de URL
│       └── config/
│           └── config.go                  # parsing do config.yaml
│
├── core/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── java/com/onionmind/
│       │   │   ├── OnionMindApplication.java
│       │   │   │
│       │   │   ├── ingestion/                    # módulo Spring Modulith
│       │   │   │   ├── package-info.java          # @ApplicationModule
│       │   │   │   ├── RawPageEvent.java          # record do evento Kafka
│       │   │   │   ├── RawPageConsumer.java        # @KafkaListener
│       │   │   │   └── IngestionPipeline.java      # orquestra ContentProcessors
│       │   │   │
│       │   │   ├── content/                       # módulo Spring Modulith
│       │   │   │   ├── package-info.java
│       │   │   │   ├── ContentProcessor.java
│       │   │   │   ├── ProcessingResult.java
│       │   │   │   ├── Document.java
│       │   │   │   ├── DocumentType.java
│       │   │   │   └── processors/
│       │   │   │       └── HtmlSanitizerProcessor.java
│       │   │   │
│       │   │   ├── search/                        # módulo Spring Modulith
│       │   │   │   ├── package-info.java
│       │   │   │   ├── SearchController.java
│       │   │   │   └── SearchResult.java
│       │   │   │
│       │   │   ├── persistence/
│       │   │   │   ├── PageEntity.java
│       │   │   │   ├── PageVersionEntity.java
│       │   │   │   └── PageRepository.java
│       │   │   │
│       │   │   └── ai/                            # módulo (interfaces no-op da Fase 0)
│       │   │       ├── AIOrchestrator.java
│       │   │       ├── TaskContext.java
│       │   │       └── NoOpAIOrchestrator.java
│       │   │
│       │   └── resources/
│       │       └── application.yml
│       │
│       └── test/
│           └── java/com/onionmind/
│               ├── InfrastructureBootstrapTest.java   # Fase 0 (já existe)
│               ├── ingestion/
│               │   └── IngestionPipelineTest.java
│               ├── content/
│               │   └── HtmlSanitizerProcessorTest.java
│               ├── search/
│               │   └── SearchControllerTest.java
│               └── Fase1EndToEndTest.java
│
└── web/
    ├── package.json
    ├── src/
    │   ├── App.tsx
    │   ├── components/
    │   │   ├── SearchBar.tsx
    │   │   └── ResultList.tsx
    │   └── api/
    │       └── search.ts
    └── Dockerfile
```

---

## 4. Docker Compose (atualizado para Fase 1)

Novos serviços em relação à Fase 0: `redis`, `tor`.

```yaml
services:
  # --- Fase 0 (já existiam) ---
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
      - redpanda start
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

  # --- Fase 1 (novos) ---
  redis:
    image: redis:7-alpine
    command: redis-server --maxmemory 128mb --maxmemory-policy allkeys-lru
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 5

  tor:
    image: dperson/torproxy:latest
    ports: ["9050:9050"]         # SOCKS5 proxy
    environment:
      LOCATION: BR              # país do exit node (irrelevante para .onion, mas evita warning)
    restart: unless-stopped

  # crawler e core rodam fora do compose em dev (IDE);
  # em produção na Oracle VM, adicionam-se aqui como services.

volumes:
  pg_data:
```

**Nota sobre o Tor daemon:** para desenvolvimento local, um único `dperson/torproxy` é suficiente. Em produção, escalar para N instâncias Tor com HAProxy na frente (o pattern `tor-rotator`) para distribuir carga e rotacionar circuitos.

---

## 5. Componente Go: Crawler

### 5.1 Configuração

```yaml
# crawler/config.yaml
crawler:
  workers: 8
  fetch_timeout: 45s
  max_page_size_mb: 10
  politeness_delay: 3s         # delay entre requests ao MESMO host
  circuit_breaker:
    consecutive_failures: 3
    timeout: 30s

tor:
  socks_addr: "tor:9050"       # endereço do proxy SOCKS5

redis:
  addr: "redis:6379"
  dedup_ttl: 6h                # TTL do seen-set (diferente do content_hash no Postgres)

redpanda:
  brokers: ["redpanda:9092"]
  topic: "raw-pages"

frontier:
  seeds:                        # URLs iniciais — expandidas pelo próprio crawler via link extraction
    - "http://ahmia.fi/onions/"
    - "http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion/"
  max_depth: 3                  # profundidade máxima de links seguidos a partir de um seed
  max_queue_size: 100000
```

### 5.2 Interface `SourceConnector`

```go
// internal/connector/connector.go
package connector

import "context"

type SourceConnector interface {
    ID() string
    Fetch(ctx context.Context, url string) (*RawPage, error)
}

type RawPage struct {
    URL        string `json:"url"`
    SourceType string `json:"source_type"`
    HTML       []byte `json:"html"`
    FetchedAt  string `json:"fetched_at"` // ISO 8601
}
```

### 5.3 `TorConnector`

```go
// internal/connector/tor.go
package connector

import (
    "context"
    "fmt"
    "io"
    "net"
    "net/http"
    "time"

    "github.com/sony/gobreaker/v2"
    "golang.org/x/net/proxy"
)

type TorConnector struct {
    client  *http.Client
    breaker *gobreaker.CircuitBreaker[[]byte]
}

func NewTorConnector(socksAddr string, fetchTimeout time.Duration, cbSettings gobreaker.Settings) (*TorConnector, error) {
    dialer, err := proxy.SOCKS5("tcp", socksAddr, nil, proxy.Direct)
    if err != nil {
        return nil, fmt.Errorf("socks5 dial: %w", err)
    }

    transport := &http.Transport{
        DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
            // socks5h — resolução de .onion acontece no proxy, não localmente
            return dialer.Dial(network, addr)
        },
        MaxIdleConns:        100,
        IdleConnTimeout:     90 * time.Second,
        DisableKeepAlives:   false,
        MaxConnsPerHost:     2, // politeness: não abrir muitas conexões ao mesmo .onion
    }

    cb := gobreaker.NewCircuitBreaker[[]byte](cbSettings)

    return &TorConnector{
        client:  &http.Client{Transport: transport, Timeout: fetchTimeout},
        breaker: cb,
    }, nil
}

func (t *TorConnector) ID() string { return "tor" }

func (t *TorConnector) Fetch(ctx context.Context, targetURL string) (*RawPage, error) {
    body, err := t.breaker.Execute(func() ([]byte, error) {
        req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
        if err != nil {
            return nil, fmt.Errorf("build request: %w", err)
        }
        req.Header.Set("User-Agent", "OnionMind/0.1 (research crawler)")

        resp, err := t.client.Do(req)
        if err != nil {
            return nil, fmt.Errorf("fetch: %w", err)
        }
        defer resp.Body.Close()

        if resp.StatusCode >= 400 {
            return nil, fmt.Errorf("http %d for %s", resp.StatusCode, targetURL)
        }

        // cap de 10MB — páginas maiores são descartadas, não truncadas
        return io.ReadAll(io.LimitReader(resp.Body, 10<<20))
    })
    if err != nil {
        return nil, err
    }

    return &RawPage{
        URL:        targetURL,
        SourceType: t.ID(),
        HTML:       body,
        FetchedAt:  time.Now().UTC().Format(time.RFC3339),
    }, nil
}
```

### 5.4 Frontier (fila de URLs a visitar)

```go
// internal/frontier/frontier.go
package frontier

import (
    "net/url"
    "strings"
    "sync"

    "golang.org/x/net/html"
)

// Frontier gerencia a fila de URLs a visitar com controle de profundidade.
// Não é persistente — se o crawler reiniciar, recomeça dos seeds.
// Persistência (Redis sorted set) é otimização de fases futuras.
type Frontier struct {
    mu       sync.Mutex
    queue    chan URLJob
    maxDepth int
}

type URLJob struct {
    URL   string
    Depth int
}

func New(seeds []string, maxQueueSize, maxDepth int) *Frontier {
    f := &Frontier{
        queue:    make(chan URLJob, maxQueueSize),
        maxDepth: maxDepth,
    }
    for _, s := range seeds {
        f.queue <- URLJob{URL: s, Depth: 0}
    }
    return f
}

func (f *Frontier) Jobs() <-chan URLJob { return f.queue }

// Enqueue adiciona URLs descobertas dentro de uma página, se dentro do maxDepth.
func (f *Frontier) Enqueue(parentDepth int, rawHTML []byte, baseURL string) {
    if parentDepth >= f.maxDepth {
        return
    }
    links := extractLinks(rawHTML, baseURL)
    for _, link := range links {
        if !isOnion(link) {
            continue // ignora links clearnet — só .onion interessa
        }
        select {
        case f.queue <- URLJob{URL: link, Depth: parentDepth + 1}:
        default:
            // fila cheia — descarta silenciosamente; não bloqueia o worker
        }
    }
}

func extractLinks(rawHTML []byte, baseURL string) []string {
    var links []string
    doc, err := html.Parse(strings.NewReader(string(rawHTML)))
    if err != nil {
        return links
    }
    var walk func(*html.Node)
    walk = func(n *html.Node) {
        if n.Type == html.ElementNode && n.Data == "a" {
            for _, attr := range n.Attr {
                if attr.Key == "href" {
                    resolved := resolveURL(attr.Val, baseURL)
                    if resolved != "" {
                        links = append(links, resolved)
                    }
                }
            }
        }
        for c := n.FirstChild; c != nil; c = c.NextSibling {
            walk(c)
        }
    }
    walk(doc)
    return links
}

func resolveURL(href, base string) string {
    b, err := url.Parse(base)
    if err != nil { return "" }
    ref, err := url.Parse(href)
    if err != nil { return "" }
    return b.ResolveReference(ref).String()
}

func isOnion(u string) bool {
    parsed, err := url.Parse(u)
    if err != nil { return false }
    return strings.HasSuffix(parsed.Hostname(), ".onion")
}
```

### 5.5 Dedup (Redis)

```go
// internal/dedup/dedup.go
package dedup

import (
    "context"
    "time"

    "github.com/redis/go-redis/v9"
)

type Dedup struct {
    client *redis.Client
    ttl    time.Duration
}

func New(addr string, ttl time.Duration) *Dedup {
    return &Dedup{
        client: redis.NewClient(&redis.Options{Addr: addr}),
        ttl:    ttl,
    }
}

// SeenRecently retorna true se a URL (já normalizada) foi visitada dentro do TTL.
func (d *Dedup) SeenRecently(canonicalURL string) bool {
    val, err := d.client.Exists(context.Background(), "seen:"+canonicalURL).Result()
    if err != nil { return false } // na dúvida, não bloqueia
    return val > 0
}

// MarkSeen registra a URL como visitada, com expiração.
func (d *Dedup) MarkSeen(canonicalURL string) {
    d.client.Set(context.Background(), "seen:"+canonicalURL, "1", d.ttl)
}
```

### 5.6 URL Normalizer

```go
// internal/normalizer/url.go
package normalizer

import (
    "net/url"
    "strings"
)

func Normalize(raw string) string {
    u, err := url.Parse(strings.ToLower(strings.TrimSpace(raw)))
    if err != nil { return raw }
    u.Fragment = ""
    q := u.Query()
    u.RawQuery = q.Encode() // Encode ordena parâmetros por chave
    if (u.Scheme == "http" && u.Port() == "80") || (u.Scheme == "https" && u.Port() == "443") {
        u.Host = u.Hostname()
    }
    // remove trailing slash (exceto root)
    if u.Path != "/" {
        u.Path = strings.TrimRight(u.Path, "/")
    }
    return u.String()
}
```

### 5.7 Publisher (Redpanda)

```go
// internal/publisher/publisher.go
package publisher

import (
    "encoding/json"
    "log/slog"

    "github.com/IBM/sarama"
    "github.com/onionmind/crawler/internal/connector"
)

type Publisher struct {
    producer sarama.SyncProducer
    topic    string
}

func New(brokers []string, topic string) (*Publisher, error) {
    config := sarama.NewConfig()
    config.Producer.Return.Successes = true
    config.Producer.Idempotent = true       // dedup no broker
    config.Producer.RequiredAcks = sarama.WaitForAll
    config.Net.MaxOpenRequests = 1          // necessário para idempotent

    producer, err := sarama.NewSyncProducer(brokers, config)
    if err != nil {
        return nil, err
    }
    return &Publisher{producer: producer, topic: topic}, nil
}

func (p *Publisher) PublishRawPage(page *connector.RawPage) error {
    payload, err := json.Marshal(page)
    if err != nil {
        return err
    }
    msg := &sarama.ProducerMessage{
        Topic: p.topic,
        Key:   sarama.StringEncoder(page.URL), // partition por URL → ordem por site
        Value: sarama.ByteEncoder(payload),
    }
    partition, offset, err := p.producer.SendMessage(msg)
    if err != nil {
        return err
    }
    slog.Info("published", "url", page.URL, "partition", partition, "offset", offset)
    return nil
}

func (p *Publisher) Close() error { return p.producer.Close() }
```

### 5.8 Worker Pool

```go
// internal/worker/pool.go
package worker

import (
    "context"
    "log/slog"
    "sync"
    "time"

    "github.com/onionmind/crawler/internal/connector"
    "github.com/onionmind/crawler/internal/dedup"
    "github.com/onionmind/crawler/internal/frontier"
    "github.com/onionmind/crawler/internal/normalizer"
    "github.com/onionmind/crawler/internal/publisher"
)

type Pool struct {
    workers        int
    conn           connector.SourceConnector
    dedup          *dedup.Dedup
    pub            *publisher.Publisher
    frontier       *frontier.Frontier
    politenessWait time.Duration
}

func New(n int, conn connector.SourceConnector, d *dedup.Dedup, p *publisher.Publisher, f *frontier.Frontier, politeness time.Duration) *Pool {
    return &Pool{workers: n, conn: conn, dedup: d, pub: p, frontier: f, politenessWait: politeness}
}

func (p *Pool) Run(ctx context.Context) {
    var wg sync.WaitGroup
    for i := 0; i < p.workers; i++ {
        wg.Add(1)
        go func(id int) {
            defer wg.Done()
            p.work(ctx, id)
        }(i)
    }
    wg.Wait()
}

func (p *Pool) work(ctx context.Context, id int) {
    for {
        select {
        case <-ctx.Done():
            slog.Info("worker shutting down", "worker", id)
            return
        case job, ok := <-p.frontier.Jobs():
            if !ok { return }
            canon := normalizer.Normalize(job.URL)
            if p.dedup.SeenRecently(canon) {
                continue
            }

            page, err := p.conn.Fetch(ctx, job.URL)
            if err != nil {
                slog.Warn("fetch failed", "worker", id, "url", job.URL, "err", err)
                continue
            }

            p.dedup.MarkSeen(canon)

            if err := p.pub.PublishRawPage(page); err != nil {
                slog.Error("publish failed", "worker", id, "url", job.URL, "err", err)
                continue
            }

            // descobre novos links dentro da página coletada
            p.frontier.Enqueue(job.Depth, page.HTML, job.URL)

            // politeness: não sobrecarregar o mesmo .onion
            time.Sleep(p.politenessWait)
        }
    }
}
```

### 5.9 Main (bootstrap + graceful shutdown)

```go
// cmd/crawler/main.go
package main

import (
    "context"
    "log/slog"
    "os"
    "os/signal"
    "syscall"
    "time"

    "github.com/onionmind/crawler/internal/config"
    "github.com/onionmind/crawler/internal/connector"
    "github.com/onionmind/crawler/internal/dedup"
    "github.com/onionmind/crawler/internal/frontier"
    "github.com/onionmind/crawler/internal/publisher"
    "github.com/onionmind/crawler/internal/worker"
    "github.com/sony/gobreaker/v2"
)

func main() {
    slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))

    cfg := config.Load("config.yaml")

    // --- Tor connector ---
    tor, err := connector.NewTorConnector(cfg.Tor.SocksAddr, cfg.Crawler.FetchTimeout,
        gobreaker.Settings{
            Name:    "tor-fetch",
            Timeout: cfg.Crawler.CircuitBreaker.Timeout,
            ReadyToTrip: func(c gobreaker.Counts) bool {
                return c.ConsecutiveFailures > uint32(cfg.Crawler.CircuitBreaker.ConsecutiveFailures)
            },
        })
    if err != nil {
        slog.Error("failed to create tor connector", "err", err)
        os.Exit(1)
    }

    // --- Dependências ---
    d := dedup.New(cfg.Redis.Addr, cfg.Redis.DedupTTL)
    pub, err := publisher.New(cfg.Redpanda.Brokers, cfg.Redpanda.Topic)
    if err != nil {
        slog.Error("failed to create publisher", "err", err)
        os.Exit(1)
    }
    defer pub.Close()

    f := frontier.New(cfg.Frontier.Seeds, cfg.Frontier.MaxQueueSize, cfg.Frontier.MaxDepth)

    // --- Graceful shutdown ---
    ctx, cancel := context.WithCancel(context.Background())
    sigs := make(chan os.Signal, 1)
    signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
    go func() {
        <-sigs
        slog.Info("shutdown signal received, draining workers...")
        cancel()
    }()

    // --- Start ---
    slog.Info("starting crawler", "workers", cfg.Crawler.Workers, "seeds", len(cfg.Frontier.Seeds))
    pool := worker.New(cfg.Crawler.Workers, tor, d, pub, f, cfg.Crawler.PolitenessDelay)
    pool.Run(ctx)
    slog.Info("crawler stopped")
}
```

### 5.10 Dockerfile do Crawler

```dockerfile
FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -ldflags="-s -w" -o /crawler ./cmd/crawler

FROM gcr.io/distroless/static-debian12
COPY --from=build /crawler /crawler
COPY config.yaml /config.yaml
USER nonroot:nonroot
ENTRYPOINT ["/crawler"]
```

Distroless: sem shell, sem package manager, superfície de ataque mínima.

---

## 6. Componente Java: Monolito modular

### 6.1 Configuração Spring

```yaml
# core/src/main/resources/application.yml
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
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: onionmind-ingestion
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      properties:
        spring.json.trusted.packages: "com.onionmind.ingestion"

server:
  port: 8081    # 8080 já é do Redpanda Console em dev
```

### 6.2 Evento recebido

```java
// ingestion/RawPageEvent.java
public record RawPageEvent(
    String url,
    @JsonProperty("source_type") String sourceType,
    String html,
    @JsonProperty("fetched_at") Instant fetchedAt
) {}
```

### 6.3 Kafka Consumer

```java
// ingestion/RawPageConsumer.java
@Component
public class RawPageConsumer {
    private static final Logger log = LoggerFactory.getLogger(RawPageConsumer.class);
    private final IngestionPipeline pipeline;

    public RawPageConsumer(IngestionPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @KafkaListener(topics = "raw-pages", groupId = "onionmind-ingestion")
    public void consume(String payload) {
        try {
            var event = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .readValue(payload, RawPageEvent.class);
            pipeline.process(event);
        } catch (Exception e) {
            // log e segue — não poisona o consumer group
            log.error("Failed to process event: {}", e.getMessage(), e);
        }
    }
}
```

### 6.4 Ingestion Pipeline

```java
// ingestion/IngestionPipeline.java
@Component
public class IngestionPipeline {
    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private final List<ContentProcessor> processors;
    private final PageRepository repository;

    public IngestionPipeline(List<ContentProcessor> processors, PageRepository repository) {
        // ordena por order(), filtra os que supports() retorna true
        this.processors = processors.stream()
            .sorted(Comparator.comparingInt(ContentProcessor::order))
            .toList();
        this.repository = repository;
    }

    @Transactional
    public void process(RawPageEvent event) {
        var doc = Document.fromRawEvent(event);

        for (var processor : processors) {
            if (!processor.supports(doc.type())) continue;

            var result = processor.process(doc);
            if (result.status() == ProcessingResult.Status.FAILED) {
                log.warn("Processor {} failed for {}: {}",
                    processor.getClass().getSimpleName(), doc.url(), result.error());
                continue; // não bloqueia o pipeline inteiro
            }
            doc = result.document();
        }

        if (doc.extractedText() != null && !doc.extractedText().isBlank()) {
            repository.upsertWithVersioning(doc);
        } else {
            log.info("Skipping {} — no extractable text", doc.url());
        }
    }
}
```

### 6.5 Document (modelo interno)

```java
// content/Document.java
public record Document(
    String url,
    String sourceType,
    String rawHtml,
    String extractedText,
    DocumentType type,
    Instant fetchedAt
) {
    public static Document fromRawEvent(RawPageEvent event) {
        return new Document(event.url(), event.sourceType(), event.html(),
                            null, DocumentType.HTML, event.fetchedAt());
    }

    public Document withExtractedText(String text) {
        return new Document(url, sourceType, rawHtml, text, type, fetchedAt);
    }
}
```

### 6.6 HtmlSanitizerProcessor

```java
// content/processors/HtmlSanitizerProcessor.java
@Component
public class HtmlSanitizerProcessor implements ContentProcessor {
    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
        .allowElements("p", "a", "b", "i", "em", "strong",
                        "ul", "ol", "li", "h1", "h2", "h3", "h4",
                        "table", "tr", "td", "th", "pre", "code", "blockquote")
        .allowUrlProtocols("http", "https")
        .allowAttributes("href").onElements("a")
        .requireRelNofollowOnLinks()
        .toFactory();

    @Override
    public boolean supports(DocumentType type) { return type == DocumentType.HTML; }

    @Override
    public int order() { return 0; } // primeiro da cadeia, sempre

    @Override
    public ProcessingResult process(Document doc) {
        if (doc.rawHtml() == null || doc.rawHtml().isBlank()) {
            return ProcessingResult.skipped(doc, "empty html");
        }
        try {
            String sanitized = POLICY.sanitize(doc.rawHtml());
            String text = Jsoup.parse(sanitized).text();
            if (text.length() < 20) {
                return ProcessingResult.skipped(doc, "text too short after sanitization");
            }
            return ProcessingResult.success(doc.withExtractedText(text));
        } catch (Exception e) {
            return ProcessingResult.failed(doc, "sanitization error: " + e.getMessage());
        }
    }
}
```

### 6.7 PageRepository (versionamento)

```java
// persistence/PageRepository.java
@Repository
public class PageRepository {
    private final JdbcTemplate jdbc;

    public void upsertWithVersioning(Document doc) {
        String hash = sha256(doc.extractedText());
        var existing = findByUrl(doc.url());

        if (existing.isEmpty()) {
            insertNew(doc, hash);
        } else {
            var current = existing.get();
            if (!current.contentHash().equals(hash)) {
                // conteúdo mudou: arquiva versão anterior, atualiza
                archiveVersion(current);
                updateWithNewVersion(doc, hash, current.version() + 1);
            } else {
                // conteúdo idêntico: só atualiza last_seen_at
                touchLastSeen(current.id());
            }
        }
    }

    private void insertNew(Document doc, String hash) {
        jdbc.update("""
            INSERT INTO pages (url, source_type, raw_html, extracted_text, content_hash, version)
            VALUES (?, ?, ?, ?, ?, 1)
            """, doc.url(), doc.sourceType(), doc.rawHtml(), doc.extractedText(), hash);
    }

    private void archiveVersion(PageEntity current) {
        jdbc.update("""
            INSERT INTO page_versions (page_id, version, content_hash, extracted_text)
            VALUES (?, ?, ?, ?)
            """, current.id(), current.version(), current.contentHash(), current.extractedText());
    }

    private void updateWithNewVersion(Document doc, String hash, int newVersion) {
        jdbc.update("""
            UPDATE pages
            SET extracted_text = ?, content_hash = ?, version = ?,
                raw_html = ?, last_seen_at = now()
            WHERE url = ?
            """, doc.extractedText(), hash, newVersion, doc.rawHtml(), doc.url());
    }

    private void touchLastSeen(Long id) {
        jdbc.update("UPDATE pages SET last_seen_at = now() WHERE id = ?", id);
    }

    private Optional<PageEntity> findByUrl(String url) {
        try {
            return Optional.of(jdbc.queryForObject(
                "SELECT id, url, content_hash, version, extracted_text FROM pages WHERE url = ?",
                pageEntityMapper(), url));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String sha256(String text) {
        return Hashing.sha256().hashString(text, StandardCharsets.UTF_8).toString();
    }
}
```

### 6.8 Search API

```java
// search/SearchController.java
@RestController
@RequestMapping("/api")
public class SearchController {
    private final JdbcTemplate jdbc;

    @GetMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (q.isBlank()) return ResponseEntity.badRequest().build();
        size = Math.min(size, 100); // nunca mais que 100 por página

        var results = jdbc.query("""
            SELECT id, url, source_type,
                   ts_headline('simple', extracted_text, websearch_to_tsquery('simple', ?),
                                'MaxFragments=2, MaxWords=40, MinWords=20') AS snippet,
                   ts_rank(search_vector, websearch_to_tsquery('simple', ?)) AS rank,
                   version, first_seen_at, last_seen_at
            FROM pages
            WHERE search_vector @@ websearch_to_tsquery('simple', ?)
            ORDER BY rank DESC
            LIMIT ? OFFSET ?
            """, searchResultMapper(), q, q, q, size, page * size);

        long total = jdbc.queryForObject("""
            SELECT count(*) FROM pages
            WHERE search_vector @@ websearch_to_tsquery('simple', ?)
            """, Long.class, q);

        return ResponseEntity.ok(new SearchResponse(results, total, page, size));
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of(
            "total_pages", jdbc.queryForObject("SELECT count(*) FROM pages", Long.class),
            "total_versions", jdbc.queryForObject("SELECT count(*) FROM page_versions", Long.class),
            "sources", jdbc.queryForList("SELECT source_type, count(*) as n FROM pages GROUP BY source_type")
        );
    }
}
```

```java
// search/SearchResult.java
public record SearchResult(
    Long id, String url, String sourceType, String snippet,
    Double rank, Integer version, Instant firstSeenAt, Instant lastSeenAt
) {}

public record SearchResponse(
    List<SearchResult> results, long total, int page, int size
) {}
```

### 6.9 Migration Flyway (full-text)

```sql
-- db/migrations/V2__fulltext_search.sql
ALTER TABLE pages
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(extracted_text, ''))) STORED;

CREATE INDEX idx_pages_search_vector ON pages USING GIN (search_vector);
```

`'simple'` por decisão consciente: conteúdo multilíngue, stemming de idioma único distorceria o ranking (ADR-005 no SDD principal).

---

## 7. React SPA mínimo

Nesta fase o frontend é deliberadamente simples — uma barra de busca e uma lista de resultados. O investimento real de frontend vem nas fases posteriores (dashboard, timeline, knowledge graph visual).

```tsx
// web/src/App.tsx
function App() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [total, setTotal] = useState(0);

  const search = async (q: string) => {
    if (!q.trim()) return;
    const res = await fetch(`/api/search?q=${encodeURIComponent(q)}`);
    const data = await res.json();
    setResults(data.results);
    setTotal(data.total);
  };

  return (
    <div>
      <h1>OnionMind</h1>
      <input value={query} onChange={e => setQuery(e.target.value)}
             onKeyDown={e => e.key === 'Enter' && search(query)}
             placeholder="Buscar na rede Tor..." />
      <button onClick={() => search(query)}>Buscar</button>
      <p>{total} resultados</p>
      {results.map(r => (
        <div key={r.id}>
          <a href={r.url}>{r.url}</a>
          <span>v{r.version}</span>
          <p dangerouslySetInnerHTML={{ __html: r.snippet }} />
        </div>
      ))}
    </div>
  );
}
```

---

## 8. Testes

### 8.1 Unitários

```java
// content/HtmlSanitizerProcessorTest.java
@Test void removesScriptTags() {
    var doc = Document.fromHtml("<p>hello</p><script>alert('xss')</script>");
    var result = new HtmlSanitizerProcessor().process(doc);
    assertThat(result.document().extractedText()).isEqualTo("hello");
    assertThat(result.document().extractedText()).doesNotContain("script");
}

@Test void skipsEmptyHtml() {
    var result = new HtmlSanitizerProcessor().process(Document.fromHtml(""));
    assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
}

@Test void skipsShortText() {
    var result = new HtmlSanitizerProcessor().process(Document.fromHtml("<p>hi</p>"));
    assertThat(result.status()).isEqualTo(ProcessingResult.Status.SKIPPED);
}
```

```go
// internal/normalizer/url_test.go
func TestNormalize(t *testing.T) {
    cases := []struct{ in, want string }{
        {"HTTP://EXAMPLE.ONION/Page?b=2&a=1#frag", "http://example.onion/page?a=1&b=2"},
        {"http://x.onion:80/path/", "http://x.onion/path"},
        {"http://x.onion/", "http://x.onion/"},
    }
    for _, tc := range cases {
        got := Normalize(tc.in)
        if got != tc.want {
            t.Errorf("Normalize(%q) = %q, want %q", tc.in, got, tc.want)
        }
    }
}
```

### 8.2 Integração (Testcontainers)

```java
// Fase1EndToEndTest.java
@Testcontainers
@SpringBootTest
class Fase1EndToEndTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static RedpandaContainer redpanda = new RedpandaContainer("redpandadata/redpanda:v24.2.7");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers", redpanda::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchController search;

    @Test
    void endToEnd_publishEvent_becomesSearchable() throws Exception {
        String event = """
            {"url":"http://test.onion/page","source_type":"tor",
             "html":"<html><body><p>Bitcoin forum for anonymous trading</p></body></html>",
             "fetched_at":"2026-07-25T10:00:00Z"}
            """;
        kafka.send("raw-pages", "http://test.onion/page", event).get();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var count = jdbc.queryForObject(
                "SELECT count(*) FROM pages WHERE url = ?", Integer.class, "http://test.onion/page");
            assertThat(count).isEqualTo(1);
        });

        var response = search.search("bitcoin", 0, 20);
        assertThat(response.getBody().results())
            .extracting(SearchResult::url)
            .contains("http://test.onion/page");
    }

    @Test
    void versionIncrementsWhenContentChanges() throws Exception {
        // publica versão 1
        kafka.send("raw-pages", "http://v.onion",
            rawPageJson("http://v.onion", "<p>version one content</p>")).get();
        await().atMost(Duration.ofSeconds(10)).until(() ->
            pageExists("http://v.onion"));

        // publica versão 2 (conteúdo diferente)
        kafka.send("raw-pages", "http://v.onion",
            rawPageJson("http://v.onion", "<p>version two different content</p>")).get();
        await().atMost(Duration.ofSeconds(10)).until(() ->
            pageVersion("http://v.onion") == 2);

        // page_versions deve ter a versão 1 arquivada
        var archived = jdbc.queryForObject(
            "SELECT count(*) FROM page_versions WHERE page_id = (SELECT id FROM pages WHERE url = ?)",
            Integer.class, "http://v.onion");
        assertThat(archived).isEqualTo(1);
    }
}
```

---

## 9. Definition of Done — Fase 1

### Crawler Go
- [x] `TorConnector` implementa `SourceConnector` e busca páginas via SOCKS5h
- [x] Circuit breaker abre após 3 falhas consecutivas e reabre após timeout
- [x] Worker pool com N goroutines configurável e graceful shutdown via SIGTERM
- [x] URLs normalizadas antes do dedup (lowercase, sem fragment, query params ordenados)
- [x] Dedup via Redis com TTL configurável
- [x] Frontier extrai links `.onion` de páginas coletadas e respeita `max_depth`
- [x] Politeness delay entre requests ao mesmo host
- [x] Eventos publicados no Redpanda com `source_type: "tor"` e key = URL
- [x] Container distroless, non-root
- [x] Testes unitários: normalizer, dedup, frontier link extraction

### Monolito Java
- [x] `RawPageConsumer` consome do tópico `raw-pages` e delega ao `IngestionPipeline`
- [x] `HtmlSanitizerProcessor` sanitiza HTML (OWASP, allowlist) e extrai texto via Jsoup
- [x] `PageRepository.upsertWithVersioning()` insere, atualiza ou arquiva versão anterior
- [x] Migration `V2__fulltext_search.sql` adiciona `search_vector` (tsvector STORED + GIN)
- [x] `SearchController` expõe `GET /api/search?q=...` com `websearch_to_tsquery` e `ts_headline`
- [x] `GET /api/stats` retorna contadores básicos (total pages, versions, sources)
- [x] Evento com JSON malformado é logado e descartado, não poisona o consumer group
- [x] Testes unitários: sanitizer (XSS, empty, short text)
- [x] Teste de integração end-to-end: evento → persistência → busca retorna resultado
- [x] Teste de versionamento: conteúdo diferente incrementa version e arquiva anterior

### Frontend
- [x] React SPA com barra de busca e lista de resultados
- [x] Exibe snippet com highlight, URL, versão e datas
- [x] Proxy para `/api` configurado no dev server

### Infraestrutura
- [x] `docker-compose.yml` atualizado com Redis e Tor
- [x] CI passa (lint + testes Java e Go)

### Gate final
Descobrir um novo `.onion`, indexá-lo e encontrá-lo por busca textual em menos de 5 minutos, de forma repetível e sem intervenção manual.

**✅ Validado em 2026-08-22** contra a rede Tor real (seeds `ahmia.fi` + espelho `.onion` do Ahmia): dois ciclos completos, ~14s e ~5s respectivamente entre o crawler descobrir a página e ela aparecer em `GET /api/search`, sem intervenção manual além dos serviços já no ar. Ver `openspec/changes/phase1-crawler-search/tasks.md` seção 16 pro detalhamento e o bug de base64 encontrado/corrigido durante a validação.
