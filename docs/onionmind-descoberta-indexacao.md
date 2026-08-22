# OnionMind — Descoberta de Páginas e Indexação (SDD)

**Escopo:** este documento detalha dois subsistemas que atravessam várias fases do roadmap principal — como o OnionMind encontra páginas `.onion` (descoberta) e como o conteúdo coletado se torna pesquisável (indexação). Complementa o SDD principal e o SDD da Fase 1; não os substitui.

---

## 1. Dois problemas com naturezas diferentes

Descoberta e indexação costumam ser tratadas como uma coisa só — "o crawler indexa páginas" — mas são dois problemas com modos de falha completamente diferentes, e vale a pena separá-los desde o desenho.

**Descoberta** responde "quais URLs existem?". O problema central é incerteza: um `.onion` pode estar no ar agora e desaparecer amanhã; um link pode levar a uma armadilha de paginação infinita; uma fonte de seeds pode secar. Descoberta nunca "termina" — é um processo contínuo.

**Indexação** responde "o que fazer com o conteúdo que já temos em mãos?". O problema central é representação: como transformar HTML bruto em algo que uma consulta consegue encontrar, com que granularidade, e como manter isso atualizado quando o conteúdo muda. Indexação tem um ponto de conclusão claro por documento — uma página processada está indexada ou não está.

---

## 2. Descoberta de páginas `.onion`

### 2.1 De onde vêm as URLs

Duas fontes, com pesos muito diferentes ao longo do tempo:

- **Seeds curados** — uma lista inicial pequena, mantida manualmente. Índices de pesquisa já existentes na rede Tor (o Ahmia é a referência mais citada como projeto aberto e de propósito declaradamente investigativo) servem como bons pontos de partida, assim como submissão manual de endereços conhecidos por quem opera o sistema.
- **Expansão via grafo de links** — depois do bootstrap inicial, a esmagadora maioria das URLs novas vem de links encontrados dentro de páginas já coletadas, não de seeds. Isso significa que a qualidade do extrator de links importa mais, no longo prazo, do que o tamanho da lista de seeds.

### 2.2 O ciclo contínuo

```
  Seeds curados + submissão manual
              │
              ▼
  ┌─────────────────────────────┐
  │  Frontier                   │◄──────────────┐
  │  fila de URLs                │                │
  │  (max_depth, max_queue_size) │                │
  └──────────────┬───────────────┘                │
                 ▼                                 │
  ┌─────────────────────────────┐                 │
  │  Worker: TorConnector.Fetch()│                 │
  │  via SOCKS5h                 │                 │
  └──────────────┬───────────────┘                 │
                 ▼                                  │
  ┌─────────────────────────────┐                  │
  │  Extrai links .onion         │                  │
  │  + filtro anti-trap          │                  │
  └──────────────┬───────────────┘                  │
                 ▼                                   │
  ┌─────────────────────────────┐                   │
  │  Sucesso → novos links       │───────────────────┘
  │  Falha → backoff (Redis)     │───────────────────┐
  └───────────────────────────────┘                   │
                                       (re-enfileira após backoff)
```

O ciclo nunca "acaba" — cada iteração pode gerar mais trabalho do que consumiu. É isso que torna crawler traps e controle de profundidade decisões de design, não detalhes de implementação.

### 2.3 Evitando armadilhas de crawler

Paginação infinita, calendários que geram uma URL nova para cada dia até o ano 9999, ou parâmetros de sessão que tornam cada visita uma URL "nova" são problemas reais em qualquer crawler, e a rede Tor não é exceção. Um heurístico simples, aplicado antes de qualquer URL entrar na fila, evita a maior parte dos casos:

```go
// internal/frontier/traps.go
package frontier

import (
    "net/url"
    "strings"
)

var sessionParamNames = map[string]bool{
    "sid": true, "sessid": true, "phpsessid": true, "token": true,
}

// looksLikeTrap detecta padrões comuns: parâmetros de sessão na URL,
// ou o mesmo segmento de path repetido (paginação circular tipo /page/2/page/2/page/2).
func looksLikeTrap(rawURL string) bool {
    u, err := url.Parse(rawURL)
    if err != nil {
        return true // URL inválida — descarta por segurança
    }

    for param := range u.Query() {
        if sessionParamNames[strings.ToLower(param)] {
            return true
        }
    }

    segments := strings.Split(strings.Trim(u.Path, "/"), "/")
    seen := map[string]int{}
    for _, s := range segments {
        seen[s]++
        if seen[s] > 2 {
            return true
        }
    }
    return false
}
```

Como segunda linha de defesa — porque nenhum heurístico pega tudo — um teto absoluto por host no `config.yaml` (`max_pages_per_host: 5000`) limita o dano de qualquer trap que passe despercebido.

### 2.4 `robots.txt` — sinal de cortesia, não fronteira de segurança

A maioria dos serviços `.onion` não publica `robots.txt`. Quando existe, vale respeitar como sinal de boa vizinhança — evita sobrecarregar serviços com recursos limitados, que é exatamente o tipo de operador que a rede Tor tende a ter. Isso é diferente da sanitização de conteúdo (seção 5 do SDD principal), que é obrigatória independente do `robots.txt` dizer alguma coisa: um `robots.txt` permissivo não torna HTML malicioso seguro de processar.

### 2.5 A natureza efêmera dos `.onion` — liveness e backoff

Um `.onion` que falha hoje pode voltar amanhã — descartar depois de uma falha é desperdiçar trabalho de descoberta já feito. A resposta é backoff exponencial por host, não retry imediato nem descarte permanente.

**Decisão importante:** esse estado vive inteiramente no Redis, no processo do crawler — não no PostgreSQL. O crawler nunca fala diretamente com o banco (seção 5 do SDD principal); adicionar uma tabela `onion_hosts` escrita pelo Go quebraria essa fronteira de segurança sem necessidade real, já que o Redis já está disponível para o dedup.

```go
// internal/liveness/liveness.go
package liveness

import (
    "context"
    "math"
    "net/url"
    "time"

    "github.com/redis/go-redis/v9"
)

type Tracker struct {
    client *redis.Client
}

func New(addr string) *Tracker {
    return &Tracker{client: redis.NewClient(&redis.Options{Addr: addr})}
}

func hostKey(rawURL string) string {
    u, _ := url.Parse(rawURL)
    return "host:" + u.Hostname()
}

// RecordSuccess zera o contador de falhas do host.
func (t *Tracker) RecordSuccess(rawURL string) {
    t.client.HSet(context.Background(), hostKey(rawURL),
        "consecutive_failures", 0, "last_success_at", time.Now().Unix())
}

// RecordFailure incrementa falhas e agenda o próximo retry com backoff exponencial,
// limitado a 7 dias — depois disso, tenta no máximo uma vez por semana, indefinidamente.
func (t *Tracker) RecordFailure(rawURL string) {
    ctx := context.Background()
    key := hostKey(rawURL)
    failures, _ := t.client.HIncrBy(ctx, key, "consecutive_failures", 1).Result()

    backoffHours := math.Min(math.Pow(2, float64(failures)), 168) // cap: 7 dias
    nextRetry := time.Now().Add(time.Duration(backoffHours) * time.Hour)
    t.client.HSet(ctx, key, "next_retry_at", nextRetry.Unix())
}

// ShouldRetry decide se um host já pode ser re-enfileirado.
func (t *Tracker) ShouldRetry(rawURL string) bool {
    ctx := context.Background()
    val, err := t.client.HGet(ctx, hostKey(rawURL), "next_retry_at").Result()
    if err != nil {
        return true // nunca falhou — pode tentar
    }
    var nextRetryUnix int64
    _, _ = fmt.Sscanf(val, "%d", &nextRetryUnix)
    return time.Now().Unix() >= nextRetryUnix
}
```

Nunca marcar como permanentemente morto — só desacelerar até "no máximo uma vez por semana". Serviços `.onion` ressuscitam com frequência suficiente para que descarte definitivo custe mais do que vale.

### 2.6 Quando o Frontier em memória deixa de ser suficiente

Na Fase 1, o Frontier é um `chan URLJob` dentro do processo Go — funciona bem para uma única instância do crawler, mas não é compartilhável entre múltiplas instâncias (cada pod teria sua própria fila isolada, duplicando trabalho).

Quando o volume justificar escalar horizontalmente o crawler (múltiplas instâncias/pods), a evolução natural não é criar um "serviço de descoberta" separado — buscar uma página sempre produz tanto conteúdo quanto novos links ao mesmo tempo, então não há uma separação limpa entre "descobrir" e "coletar". A evolução real é migrar o Frontier de canal em memória para um **Redis Sorted Set**, com o score sendo o timestamp do próximo attempt: descobertas novas entram com score = agora; falhas entram com score = agora + backoff. A mesma estrutura resolve fila de descoberta e agendamento de backoff ao mesmo tempo, e qualquer instância do crawler pode fazer `ZPOPMIN` para pegar o próximo trabalho — sem duas instâncias pegando a mesma URL. Isso substitui tanto o `chan` quanto o `liveness.Tracker` da seção 2.5 por uma única estrutura compartilhada. Não é trabalho da Fase 1 — é o próximo passo natural quando uma instância só não dá conta.

---

## 3. Indexação

### 3.1 O que muda em cada fase

| Fase | Mecanismo | Granularidade | Onde vive |
|------|-----------|----------------|-----------|
| 1 | `tsvector` + índice GIN | documento inteiro | PostgreSQL |
| 2 | embeddings vetoriais | chunk (~512 tokens) | Qdrant |
| 5 | fusão híbrida (RRF) + re-rank | ambos, combinados | PostgreSQL + Qdrant |

### 3.2 Fase 1 — indexação síncrona, sem lag

```sql
ALTER TABLE pages
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(extracted_text, ''))) STORED;

CREATE INDEX idx_pages_search_vector ON pages USING GIN (search_vector);
```

Uma vantagem real de usar o full-text nativo do Postgres em vez de um motor de busca dedicado (Elasticsearch/OpenSearch) nesta fase: `search_vector` é uma coluna **gerada**, recalculada automaticamente a cada `INSERT`/`UPDATE`. Não existe um "índice defasado" nem um job de reindexação separado rodando em paralelo — no momento em que o `UPDATE` do `PageRepository` (Fase 1 SDD, seção 6.7) commita, a página já está pesquisável. Sistemas como Elasticsearch têm um intervalo de refresh (tipicamente ~1s) entre escrever e o documento ficar buscável; aqui esse problema simplesmente não existe.

### 3.3 Correção importante: `ts_rank` não é BM25

O documento de pesquisa original (e a descrição da Fase 5) menciona "BM25 + dense + RRF" como o padrão de busca híbrida — isso é preciso para a arquitetura alvo, mas vale uma correção: a função `ts_rank`/`ts_rank_cd` nativa do Postgres, usada na Fase 1, **não é uma implementação de BM25**. É um ranking mais simples, baseado em frequência de termo e proximidade, sem a normalização por tamanho de documento que caracteriza BM25 — perfeitamente adequado para provar o pipeline, mas não é o "estado da arte" que o nome BM25 sugere.

Duas rotas para fechar essa lacuna quando a Fase 5 chegar: (a) a extensão `pg_search` (ParadeDB) adiciona scoring BM25 real dentro do próprio Postgres, sem sair do banco — coerente com a filosofia de não adicionar infraestrutura antes de precisar; ou (b) um motor de busca dedicado (OpenSearch/Elasticsearch) como serviço separado. Dado o histórico de decisões deste projeto, (a) é a recomendação — só migra para (b) se `pg_search` não aguentar o volume, o que é um teto bem mais alto do que o MVP vai testar.

### 3.4 Fase 2 — a granularidade muda: chunks, não documentos

Full-text search funciona bem no documento inteiro porque o usuário busca por palavras literais — um match em qualquer parte do texto é útil. Busca semântica não: um documento longo com um parágrafo relevante e vinte irrelevantes gera um embedding "médio" que não representa bem nenhum dos dois. Por isso, a partir da Fase 2, o texto extraído é dividido em chunks (512 tokens, ~15% de overlap) antes de virar vetor — cada chunk no Qdrant carrega `page_id` e `chunk_index` como payload, mas o conteúdo em si continua vivendo só no PostgreSQL. O Qdrant nunca é a fonte da verdade.

### 3.5 O gate de `content_hash` — por que ele não é só para a Fase 4

O versionamento (`content_hash`, seção 6.5 do SDD principal) foi desenhado pensando no diff da Fase 4, mas o uso mais valioso dele começa antes disso: **nenhum processor de IA deveria rodar em conteúdo que já foi processado e não mudou.** Sem esse gate, todo re-crawl de uma página estável (a maioria delas, na prática) geraria uma chamada de embedding ou de LLM inteiramente desperdiçada — exatamente o tipo de gasto que o Cost Optimizer (seção 7.3 do SDD principal) existe para evitar, só que na origem, antes mesmo de chegar ao roteador.

```java
@Component
public class EmbeddingProcessor implements ContentProcessor {
    private final AIOrchestrator ai;
    private final VectorStore qdrant;

    public boolean supports(DocumentType type) { return type == DocumentType.HTML; }
    public int order() { return 100; } // depois da sanitização

    public ProcessingResult process(Document doc) {
        String currentHash = sha256(doc.extractedText());

        if (qdrant.hasUnchangedEmbedding(doc.url(), currentHash)) {
            return ProcessingResult.unchanged(doc); // pula IA — conteúdo já embedado, sem mudança
        }

        var chunks = chunk(doc.extractedText(), 512, 0.15);
        for (var chunk : chunks) {
            var embedding = ai.embed(chunk.text(), TaskContext.forEmbedding(chunk.text().length()));
            qdrant.upsert(doc.url(), chunk.index(), currentHash, embedding);
        }
        return ProcessingResult.success(doc);
    }
}
```

O mesmo princípio se aplica a `SummarizerProcessor` e `ClassifierProcessor` na Fase 3 — checar o hash antes de gastar cota gratuita de LLM é a otimização de custo mais barata que existe, porque não custa nem uma chamada de API.

### 3.6 Consistência eventual entre PostgreSQL e Qdrant

Se o `EmbeddingProcessor` falhar depois que a sanitização e o full-text já commitaram, a página fica buscável por palavra-chave mas ausente da busca semântica até ser reprocessada. Isso é aceitável — é o preço normal de eventual consistency entre dois armazenamentos independentes — mas precisa ser observável, não silencioso:

```sql
-- páginas indexadas no full-text mas sem embedding correspondente (proxy: nenhum registro recente no Qdrant)
SELECT p.url, p.last_seen_at
FROM pages p
WHERE p.extracted_text IS NOT NULL
  AND p.id NOT IN (SELECT page_id FROM embedding_status WHERE content_hash = p.content_hash)
ORDER BY p.last_seen_at DESC;
```

Essa query (ou uma tabela `embedding_status` dedicada, mais barata de consultar que perguntar ao Qdrant diretamente) alimenta um job de backfill simples na Fase 2 — sem isso, a lacuna cresce silenciosamente e ninguém percebe até a busca semântica parecer "incompleta" sem motivo aparente.

### 3.7 Além do hash exato — quase-duplicatas

`content_hash` pega páginas idênticas byte a byte. Não pega páginas quase-idênticas — e a rede Tor tem um motivo específico para isso importar mais do que na web comum: clones e sites de phishing que copiam um marketplace ou fórum legítimo com pequenas alterações são comuns. Indexar cada clone como conteúdo novo infla o índice sem agregar informação real.

A ferramenta padrão para isso é **SimHash** (fingerprint de 64 bits, comparado por distância de Hamming — um threshold de 3 bits diferentes é a referência usual da literatura). Não é trabalho da Fase 1 nem da Fase 2 — fica registrado aqui como lacuna conhecida, com o ponto de encaixe já claro: entraria como mais um `ContentProcessor` (`NearDuplicateDetectorProcessor`), rodando depois da sanitização e antes do embedding, comparando o fingerprint do texto normalizado contra os já vistos recentemente (também via Redis, na mesma linha da seção 2.5).

### 3.8 Quando a indexação nativa do Postgres para de ser suficiente

GIN sobre `tsvector` escala razoavelmente bem até a casa de alguns milhões de documentos em hardware modesto — bem acima do que o MVP vai testar nas primeiras fases. Se o volume se aproximar desse teto, ou se a qualidade de ranking do `ts_rank` (seção 3.3) deixar de ser suficiente mesmo com `pg_search`, aí sim vale considerar um motor de busca dedicado — mas essa é uma decisão para revisar com dados reais de produção, não para antecipar agora.

---

## Anexo: como este documento se encaixa

Este documento detalha as seções 2 (roadmap, Fase 1–2), 6 (data design) e 8 (segurança) do SDD principal (`onionmind-sdd.md`) e complementa o SDD da Fase 1 (`onionmind-fase1-sdd.md`), sem repetir o que já está definido lá — schema completo, `ContentProcessor`, `AIOrchestrator` e Definition of Done continuam sendo a referência nos outros dois arquivos.
