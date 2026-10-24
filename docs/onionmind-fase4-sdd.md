# OnionMind — Fase 4: Knowledge Graph + Versionamento (SDD)

**Pré-requisito:** Fase 3 concluída (enriquecimento por IA, `content_hash` como gate, Event Publication Registry criado — P2-5).

**Objetivo:** relacionar entidades entre páginas e responder "o que mudou nesta categoria desde ontem" de forma correta e curta.

**Status:** código completo e testado na branch `feat/phase-4` (OpenSpec change `phase4-knowledge-graph-versioning`). O gate real com provedores de verdade está pendente (seção 9).

Documentos de referência:
- `onionmind-sdd.md` — seções 5 (Fase 4), 6.4 (Neo4j), 7.5 (versionamento e diff) e 14 (DoD).
- `onionmind-master-sdd.md` — ADR-009 e seções 6.5 e 6.6.
- `onionmind-fase3-sdd.md` — seção 13 (prontidão para a Fase 4).

---

## 1. O que entrega (e o que não entrega)

**Entrega:**
- **Entidades por página**, cada uma com chave normalizada e confiança:
  - carteiras, endereços `.onion` v3, e-mails e fingerprints PGP, por padrão, com checksum quando o formato tem;
  - pessoas, organizações, locais e tecnologias, via LLM.
- **Grafo Neo4j** que liga páginas, serviços e entidades.
- **Consulta de entidade**, que mostra quem cita e o que aparece junto.
- **Detecção de mudanças entre versões**, com resumo curto em inglês (o formato padrão de todo texto gerado por IA desde `remove-page-translation`).
- **Consulta "o que mudou"** por categoria e período, sem LLM ao vivo.

**Não entrega:**
- Alertas (`AlertCheckListener`).
- UI web do grafo e das mudanças.
- Resolução de entidades além da normalização de chave.
- Retenção de `page_versions`.
- Relação `CO_OCCURS_WITH` materializada.
- spaCy/GLiNER.
- RAG (Fase 5).

## 2. Arquitetura

```
 crawler ──raw-pages──▶ IngestionPipeline ──▶ pages (pending)
                                                  │  AiEnrichmentBackfillJob (agendado, LLM fora de transação)
                                                  ▼
                       order 10–50: idioma, resumo, categoria, ENTIDADES
                                                  │  tx curta: updateAiFields + publishEvent
                                                  ▼
                                   PageEnrichedEvent (Event Publication Registry)
                         ┌────────────────────────┴───────────────────────┐
                         ▼ async, após commit                             ▼ async, após commit
              graph.GraphProjectionListener               intelligence.ChangeDetectionListener
                 Neo4j: Page/OnionService/Entity             page_changes (pending | trivial)
                         │                                                │  ChangeSummaryJob (agendado)
                GET /api/entities/{type}/{key}                            ▼
                                                              GET /api/changes?since=&category=
```

Pontos de desenho:
- **Módulos.** `graph` depende de `ingestion` (evento) e `content` (tipos de entidade). `intelligence` depende de `ingestion`, `ai` (`summarize`) e `content` (taxonomia). O `ModularityTest` verifica as fronteiras.
- **Quarentena.** Publica `PageQuarantinedEvent`. Com ele, o grafo remove a página e as entidades órfãs, e `page_changes` da página são apagadas. O próprio `PageRepository.quarantine` também zera o texto, as entidades e o resumo das versões arquivadas. Isso fecha uma lacuna que existia desde a Fase 1: o texto bloqueado sobrevivia em `page_versions`.
- **Neo4j é opcional.** Com `graph.enabled=false`, nada conecta ao Neo4j, o health indicator dele fica desligado e `/api/entities` não existe. Busca e mudanças seguem funcionando.

## 3. Desvios em relação aos SDDs

| SDD | Implementado | Por quê |
|---|---|---|
| `PageIndexedEvent` publicado na indexação (ADR-009) | `PageEnrichedEvent` publicado ao fim do enriquecimento | Os dois consumidores precisam de entidades e categoria, que só existem depois do backfill de IA. O princípio do ADR fica intacto: `ingestion` publica e não conhece os consumidores. |
| `(:OnionService)-[:MENTIONS]->(:Entity)` | `(:Page)-[:MENTIONS]->(:Entity)` + `(:Page)-[:HOSTED_ON]->(:OnionService)` | Versão nova e quarentena atuam por página; com menção no serviço, seria preciso recontar as outras páginas. "Serviço menciona" vira um salto a mais na consulta. |
| `CO_OCCURS_WITH` materializado | Coocorrência calculada na consulta (páginas em comum) | Contador por par sob substituição e quarentena tende a ficar inconsistente. Uma consulta de dois saltos é barata dentro do teto do AuraDB Free. |
| `EntityProcessor` com spaCy/GLiNER escalando para LLM | Padrões determinísticos + LLM | spaCy/GLiNER exigiriam um sidecar Python. Os identificadores de maior valor saem de graça por padrão com checksum. |
| Diff estrutural | Diferença de conjuntos de sentenças normalizadas (dígitos viram `#`) | Reordenação não é mudança, contadores e datas não geram ruído, e não entra dependência nova. |

## 4. Dados (migration V7)

**`page_versions`:**
- Ganha `entities`, `category` e `summary` (JSONB), copiados no arquivamento **só se** a página estava em `processed`, ou seja, se o enriquecimento pertencia àquela versão.
- Ganha unique `(page_id, version)`.

**`page_changes`:**

| Coluna | Conteúdo |
|---|---|
| `page_id` (FK `ON DELETE CASCADE`), `url` | Página da mudança |
| `from_version`, `to_version` | Unique `(page_id, to_version)` |
| `added_text`, `removed_text` | Com teto `intelligence.diff.max-text-chars` |
| `entities_added`, `entities_removed` | Delta de entidades |
| `category_from`, `category_to` | Troca de categoria |
| `status` | `pending`, `trivial`, `summarized`, `failed_transient` ou `failed_permanent` |
| `attempts`, `summary` (JSONB com `text` e `confidence`), `detected_at` | Controle do resumo |

O texto do diff de uma página nova é comparado com a **última versão arquivada que tem snapshot de enriquecimento**. Se nenhuma tiver, usa a versão imediatamente anterior, sem delta de entidades. Assim, duas mudanças seguidas antes do backfill não distorcem o delta.

Uma mudança é **trivial** quando não tem delta de entidades nem de categoria e, além disso, as sentenças normalizadas são iguais ou o texto alterado tem menos de `intelligence.diff.min-changed-chars` (40). Mudança trivial não chega ao LLM nem aparece em `/api/changes`. Limite aceito: uma mudança só de preço é trivial.

## 5. Configuração

| Propriedade | Padrão | Papel |
|---|---|---|
| `graph.enabled` | `false` | Liga projeção, API de entidades e health do Neo4j |
| `graph.rebuild` | — | `true` no boot apaga o Neo4j e reprojeta a partir de `pages.entities` |
| `spring.neo4j.uri` / `NEO4J_*` | `bolt://localhost:7687` | AuraDB em produção |
| `entities.max-input-chars` | 8000 | Texto enviado ao LLM (os padrões leem tudo) |
| `entities.max-per-page` | 200 | Limita também o payload do evento |
| `intelligence.diff.min-changed-chars` | 40 | Limiar de mudança trivial (calibrar no gate real) |
| `intelligence.diff.max-text-chars` | 4000 | Trecho guardado e enviado ao resumo |
| `intelligence.summary.*` | lote 20, 5 min, 3 tentativas | `ChangeSummaryJob` |
| `events.resubmit-after` / `resubmit-interval-ms` | 10m / 5 min | `EventResubmissionJob` |
| `spring.modulith.events.completion-mode` | `delete` | Purga das publicações completadas |
| `spring.task.scheduling.pool.size` | 4 | Uma thread por job agendado |
| `spring.task.execution.pool.core-size` | 4 | Teto de listeners async simultâneos |

## 6. Cota de IA

- Cada página enriquecida passa a gastar 5 chamadas em vez de 4 (entidades).
- Cada mudança real gasta mais uma (resumo).
- Conteúdo inalterado (gate de `content_hash`) e mudança trivial não gastam nada.
- Páginas enriquecidas antes da Fase 4 só ganham entidades se forem recolocadas em `pending` (operação manual por lote, ver o README), e isso gasta cota de novo.

## 7. Testes

| Nível | O quê |
|---|---|
| Unitário | Extratores e checksums (vetores BIP-173/350, endereço gênese, onion do Tor Project), normalização de chave, validação do JSON de entidades (incluindo instrução embutida), `EntityProcessor` (falha parcial mantém as entidades de padrão), `SentenceDiff`, `ChangeSummaryJob`, listeners e controllers |
| Integrado | V7, snapshot condicional, quarentena limpando versões, escolha da base do diff, idempotência, filtros de `/api/changes`; `GraphRepository` contra Neo4j real (projeção, versão fora de ordem, quarentena, órfãos, coocorrência, injeção); rebuild a partir do Postgres |
| Eventos | `PageLifecycleEventsIntegrationTest`: os dois listeners consomem, uma falha num não bloqueia o outro, reenvio e registro vazio ao final |
| Gate | `Fase4ChangeQueryGateTest`: Kafka → ingestão → backfill → evento → diff → resumo; "o que mudou em forum desde ontem" lista só o fórum com mudança real |
| Aceitação | `features/fase4/` (7 cenários) |
| Smoke | `@mudancas`, `@grafo` (o CI exclui `@grafo`, porque o runner não tem Neo4j) |
| E2E | Uma carteira citada numa página rastreada aparece em `/api/entities` |

Verificação por mutação: o filtro trivial, a guarda de versão do grafo, o checksum BTC e o snapshot condicional, cada um desligado isoladamente, quebram ao menos um teste.

## 8. Riscos e itens futuros

- **AuraDB Free (200K nós / 400K relações):** nós `Page` contam. Medir antes de chegar perto; o plano B é Community self-hosted.
- **`page_versions` cresce sem limite.** Uma política de retenção por idade ou número de versões fica para depois.
- **Coocorrência na consulta.** Materializar com um job noturno se a latência piorar.
- **Sidecar GLiNER** atrás de `extractEntities`, se a cota de LLM apertar.
- **`summarizeChanges` dedicado**, se o resumo de diff reutilizando `summarize` ficar fraco no gate real.

## 9. Gate de saída

"O que mudou nesta categoria desde ontem" retorna resposta correta e curta.

- **Automatizado:** 🟢 `Fase4ChangeQueryGateTest` e o cenário de aceitação "O que mudou nos fóruns desde ontem".
- **Real:** 🟢 validado em 2026-10-23 (ver 9.1), com ressalvas registradas.

### 9.1 Validação real — 2026-10-23 (Tor real, Groq + Gemini reais, Ollama local; chaves de dev revogadas depois do teste)

**Setup.** Core com `ai.enabled=true` e `graph.enabled=true`, Neo4j 5 Community do compose, banco de dev. O banco já tinha 11 páginas do ahmia rastreadas em 2026-09-07. O re-crawl de hoje encontrou conteúdo diferente em 6 delas, que viraram v2. Essas são mudanças reais, com cerca de 6 semanas de intervalo, e não mudanças fabricadas.

**Resultado do gate.** `/api/changes?category=institutional&since=<ontem>` devolveu exatamente uma mudança, `/privacy/` v1→v2, com um resumo correto e curto (confidence 0.97): a página trocou "não processa dados pessoais" por "minimização de dados", com a lista do que passou a coletar e a data de atualização. `category=service` devolveu `/terms/` v1→v2 (confidence 0.96). `category=forum` devolveu `total: 0`, o que está certo, porque o corpus não tem fórum. As respostas de erro também funcionaram: `since` no futuro e categoria inválida devolveram 400.

**Grafo.** 7 páginas e 28 entidades projetadas. `/api/entities/person/juha%20nurmi` devolve a página `/legal/` e as coocorrências (e-mail, Finland, Ahmia.fi, Internet Watch Foundation). Entidade inexistente devolve 404, tipo inválido devolve 400. Erros de rótulo do LLM observados: `location:tor` e `location:onion`. É ruído aceitável para a v0.4, mas é candidato a ajuste de prompt.

**Calibração de `min-changed-chars`.** O corpus não teve nenhuma mudança trivial: as 2 mudanças tinham entre 2,1K e 2,9K caracteres alterados. O valor fica em **40**, sem dado novo para mudar. A cobertura de mudanças triviais segue nos testes de aceitação. Recalibrar quando houver fórum ou marketplace no corpus.

**Tempo.** Não foi medido de forma limpa. O primeiro ciclo usou só o Ollama em Docker (CPU, 6,2 de 7,6 GB de RAM da VM, CPU em 1000%) e não drenou em 1h. Com Groq e Gemini, as duas páginas v2 pequenas foram enriquecidas, diffadas e resumidas em poucos minutos. Cada chamada ao Groq levou entre 0,7 e 4 s.

**Bloqueios e bugs que só apareceram com infra real.** Nenhum teste com fake pegava estes:

1. **A imagem de Tor do compose está obsoleta.** `dperson/torproxy:latest` trava em `Bootstrapped 45%` e loga `missing protocols ... Relay=3-6`. A validação usou um Tor 0.4.9 temporário (Alpine). O compose precisa trocar de imagem. ✅ **Resolvido** em `fix-real-infra-findings`: `tor/Dockerfile` (Alpine 3.22, Tor 0.4.9.14), healthcheck por `Bootstrapped 100%`, sem `ExitNodes {BR}`.
2. **Os processors da Fase 3 mandam o texto inteiro ao LLM.** Só o `EntityProcessor` tem teto (`entities.max-input-chars`). Nas páginas de 1,1 a 2 MB, TRANSLATE, SUMMARIZE, CLASSIFY e DETECT_LANGUAGE recebem `groq HTTP 400 "Please reduce the length of the messages"` e `gemini HTTP 429`. Essas páginas nunca chegam a `processed`, então nunca publicam `PageEnrichedEvent` e ficam fora do grafo e do diff. ✅ **Resolvido** em `fix-real-infra-findings`: `AbstractAiProcessor` corta a entrada em `ai.max-input-chars` (8000) para todo processor de IA.
3. **O backfill de IA não tem limite de tentativas.** `AiEnrichmentBackfillJob` reprocessa `failed_transient` para sempre. Com o item 2, as 4 páginas grandes queimam chamadas reais de nuvem a cada execução. O `ChangeSummaryJob` já tem `max-attempts`, mas o backfill de páginas não. ✅ **Resolvido** em `fix-real-infra-findings`: `pages.ai_attempts` (V8) e `ai.backfill.max-attempts` (5) levam a página a `failed_permanent`; conteúdo novo zera a contagem.
4. **O tracker de cota conta requisições, não tokens.** O Groq devolveu 429 por limite de tokens por minuto, com o contador local bem abaixo do limite de requisições.
5. **O Ollama em CPU disputa a VM com o Postgres.** Com o Ollama saturando a CPU, `ChangeSummaryJob` e o `EventResubmissionJob` do Modulith tiveram `statement timeout` (limite de 2 s) em consultas triviais. O próximo ciclo se recuperou sozinho.
6. **O crawler não segue a listagem do ahmia.** O crawl só indexou URLs do próprio ahmia, porque os `.onion` da listagem não viram links que o frontier segue. O corpus ficou restrito a páginas de serviço e institucionais. ✅ **Resolvido** em `crawler-follow-onion-listings`: a causa era que `ahmia.fi/onions/` lista 16.724 endereços v3 em texto puro, separados por `<br>` e sem `<a>`. O frontier passou a extrair endereços `.onion` v3 dos nós de texto, com teto `crawler.max_links_per_page` (2000), e o crawler descarta antes de enfileirar e antes do fetch todo host da denylist de MD5 no formato do `/banned/` do ahmia (`crawler.host_denylist_path`). A validação real do corpus (mais de 100 hosts distintos) continua pendente.

**Limite aceito.** As versões v1 eram anteriores à Fase 4 e não tinham snapshot de enriquecimento. Por isso o diff dessas mudanças traz só o delta de texto, sem delta de entidades nem troca de categoria (`category_from` nulo), como o design D7 prevê.

Itens 1–3 eram segue-adiante (não bloqueavam o gate, que foi atingido) e foram resolvidos no change `fix-real-infra-findings` (`openspec/changes/archive/2026-10-24-fix-real-infra-findings/`). O Tor novo foi validado de verdade: bootstrap a 100% em ~15 s sem o aviso de protocolos, e o crawler publicou as duas seeds (`ahmia.fi` e a `.onion`). O ciclo real de backfill com as páginas de 1 a 2 MB foi rodado em 2026-10-24 (seção 9.2). Os itens 4 e 5 continuam em aberto. O item 6 foi resolvido em `crawler-follow-onion-listings`, mas ainda falta a validação real dele.

### 9.2 Ciclo real dos fixes — 2026-10-24 (Tor real, Groq + Gemini reais, Ollama local; chaves de dev revogadas depois do teste)

**Setup.** Compose com o Tor construído no repo, core com `ai.enabled`, `embedding.enabled` e `graph.enabled`, banco de dev do 9.1. No boot o Flyway aplicou a V8 (`ai_attempts`) e a V9 (drop de `translated_text`) no banco existente, sem erro. Crawl real de 5 min com 12 publicações; o crawler saiu com código 0 no SIGTERM.

**Páginas de 1 a 2 MB.** As 4 páginas grandes (`ahmia.fi/onions/`, `/onions/`, `/banned/`, `/blacklist/banned/`, 1,17 a 1,98 MB) chegaram a `processed`, com idioma, categoria e resumo. Na 9.1 elas recebiam `HTTP 400 "reduce the length"` para sempre. O contador de tentativas funcionou: duas delas registraram `ai_attempts = 1` depois de uma falha transitória e terminaram `processed` na tentativa seguinte. Nenhuma chegou a `failed_permanent`, porque nenhuma falhou 5 vezes; esse caminho segue coberto por teste. Os resumos de `/banned/` dizem "No web page content was provided to summarize": o começo da página é só uma lista de hashes MD5, então o resumo está correto, e o limite de o resumo cobrir só o começo do texto já está documentado.

**Achados novos.**

7. **O cache de IA não muda quando o prompt muda.** O `CacheDecorator` usa a chave `(tarefa, sha256(texto))`, com TTL de 24 h. Depois da troca dos prompts para inglês, a primeira página reenfileirada voltou com o resumo **em português** de ontem, servido do Redis. Para o teste, as chaves `ai-cache:*` foram apagadas. ✅ **Resolvido** em `fix-ai-cache-prompt-version`: a chave passou a ser `(tarefa, sha256(prompt + texto))`, e as entradas do prompt antigo deixam de ser lidas e expiram pelo TTL.
8. **Com o Ollama em CPU, o backfill fica muito lento.** O roteador manda tarefas curtas primeiro ao Ollama (LOCAL_FIRST), e cada uma levava cerca de 90 s para falhar (timeout de 30 s mais os retries) antes de ir para a nuvem. Com `--ai.ollama.timeout=3s` a fila drenou em poucos minutos. O Groq devolveu 429 por TPM algumas vezes (item 4), e o Gemini assumiu (`ai_request_total{provider="gemini"}`).
9. **Mudança durante o enriquecimento.** Com o crawler rodando, a listagem do ahmia mudava a cada passada, e o backfill descartava o enriquecimento ("content changed while it ran"). É o comportamento esperado; depois que o crawl parou, a fila drenou.
