# OnionMind — Fase 4: Knowledge Graph + Versionamento (SDD)

**Pré-requisito:** Fase 3 concluída (branch `feat/phase-3-llm-integration`) — `AIOrchestrator` real, `PageContentLookup`/gate por `content_hash`, `IllegalContentGuard` (HALT).
**Objetivo:** relacionar entidades entre páginas num grafo consultável e responder "o que mudou nesta categoria desde ontem" com um resumo curto e correto, sem que `ingestion`/`content` precisem saber que isso existe.
**Branch:** `feature/phase4-knowledge-graph` (criada a partir de `feat/phase-3-llm-integration`, nunca mesclada em `main`).
**Change OpenSpec:** `phase4-knowledge-graph` (`openspec/changes/phase4-knowledge-graph/` — não versionado no git, `openspec/*` está no `.gitignore` deste repo).
**Status:** código completo, 27/27 tasks de `tasks.md` fechadas. Gate de saída provado com fakes (`Fase4KnowledgeGraphGateTest`) **e** toda a suíte (incluindo `Neo4jGraphStoreTest`, `EventPublicationDurabilityTest`, `PageDiffRepositoryTest`, `AlertRuleRepositoryTest` etc.) confirmada de verdade contra Postgres/Kafka/Redis/Neo4j reais via Testcontainers em 2026-09-06 (`BUILD SUCCESSFUL`, 0 falhas). Validação contra LLM ao vivo (Ollama/Groq/Gemini) e Neo4j AuraDB de produção continua pendente — ver seção "Validação real pendente".

Documentos de referência (validados para esta fase): `onionmind-master-sdd.md` — ADR-009, seções 5, 6.5, 6.6, 10; `onionmind-sdd.md` — seção "Fase 4" e checklist de DoD; `onionmind-fase3-sdd.md` — nota P2-5, seção 13 ("Prontidão para a Fase 4").

---

## 1. O que essa fase entrega (e o que não entrega)

**Entrega — OnionMind v0.4:** toda página indexada tem suas entidades (pessoa, organização, carteira de criptomoeda, tecnologia, localização, serviço `.onion`) extraídas e persistidas num grafo (Neo4j), deduplicadas por identidade de negócio entre páginas diferentes. Toda mudança de conteúdo entre duas versões de uma página gera um resumo em linguagem natural do que mudou, consultável via API. Regras de alerta simples (palavra-chave, categoria ou tipo de entidade) geram alertas quando uma página nova ou alterada casa com elas.

**Não entrega:**
- Chat/RAG sobre o grafo ou os diffs (Fase 5).
- UI de administração de regras de alerta — só API (CRUD básico).
- Perceptual hashing/heurística de pré-filtro pra extração de entidades — `extractEntities` chama o LLM direto pra toda página elegível (D4); trocar por um pré-filtro depois é transparente pro resto do sistema.
- `NetworkPolicy`/gVisor (master-sdd sec. 11, "Fase 4+") — condicional a uma migração pra Kubernetes fora de escopo aqui.

## 2. Achados durante a implementação (divergências do design original)

O `design.md` do change OpenSpec foi escrito antes de ler o código real da Fase 3 em detalhe. Três correções relevantes, registradas aqui pra quem for ler o design.md original sem o histórico completo:

1. **`ValidatedResult` (Fase 3) não serve pra uma lista de entidades.** Ele só tem dois campos de string (`primary`/`secondary`) + confidence — pensado pra summary/category/translation/language, não pra uma lista de entidades tipadas. Foi criado `ValidatedEntities` (record próprio) e um método novo em `ValidationDecorator` (`validateEntities`), mais overloads em `CacheDecorator` (`getEntities`/`putEntities`). `DefaultAIOrchestrator.extractEntities` tem seu próprio loop de escalonamento (`executeEntities`), espelhando `execute()` sem duplicar lógica de roteamento/retry — só a validação muda de forma.
2. **`AIOrchestrator.summarize` tem prompt fixo** ("resuma esta página") — não dá pra reusar pra "resuma o que mudou entre duas versões" como o design original propunha. Foi adicionado `AIOrchestrator.summarizeDiff(previousText, currentText, ctx)`, que monta seu próprio prompt e reusa o mesmo `execute()` privado (mesma escada testada, prompt diferente).
3. **Endpoints de leitura não podem ficar centralizados em `search`.** A primeira tentativa (`search.PageInsightsController` dependendo de `graph.GraphStore`) fechou um ciclo real de módulos, pego pelo `ModularityTest`: `content → search → graph → ingestion → content` (as duas primeiras arestas já existiam da Fase 1/2; as duas últimas eram novas desta fase). Corrigido: cada módulo novo expõe seu próprio endpoint (`graph.EntityController`, `intelligence.DiffController`, `intelligence.AlertsController`/`AlertRuleController`) — nenhuma dependência de volta pra `search`/`content`.

## 3. Arquitetura

### 3.1 `PageIndexedEvent` (ADR-009)

Resolvida a nota P2-5 do SDD da Fase 3 primeiro (bloqueador conhecido): migration `V6__event_publication.sql` com o DDL oficial do `spring-modulith-events-jdbc` (schema v2), copiado literalmente do jar em vez de reescrito à mão. `spring.modulith.events.republish-outstanding-events-on-restart: true` ligado. Housekeeping (`EventPublicationHousekeepingJob`, `@Scheduled`) delega pra `EventPublicationRegistry.deleteCompletedPublicationsOlderThan(...)` — método oficial do Modulith, não uma query manual.

`IngestionPipeline.process()` publica `PageIndexedEvent(pageId, url, contentHash, version, isNewVersion)` ao fim do upsert (`upsertWithVersioning` passou a retornar um `UpsertOutcome` em vez de `void`). Página bloqueada pelo `IllegalContentGuard` (`HALT`) nunca chega a publicar o evento — o `return` acontece antes — então `graph`/`intelligence` nunca veem conteúdo em quarentena, sem precisar checar isso em runtime.

`PageContentLookup` (porta pública nova em `ingestion`) expõe texto atual, texto da versão anterior e categoria atribuída — o mínimo que `graph`/`intelligence` precisam sem tocar `ingestion.internal.PageRepository`.

### 3.2 Módulo `graph`

`GraphStore` (porta pública) + `Neo4jGraphStore` (driver Bolt oficial, sem Spring Data Neo4j — mesma filosofia de `JdbcTemplate` sobre JPA já usada no resto do projeto). `EntityExtractionListener` reage ao evento, pula se o grafo já tem esse `content_hash` pra essa página (gate autocontido — a propriedade fica no próprio nó `:Page`, não numa tabela Postgres nova), busca o texto via `PageContentLookup`, chama `AIOrchestrator.extractEntities`, e faz `MERGE` por business key (`value`) + relação `CO_OCCURS_WITH` entre entidades da mesma página. `NoOpGraphStore` cobre `graph.enabled=false` (default) — sem isso, todo `@SpringBootTest` do projeto tentaria conectar em Neo4j na subida.

### 3.3 Módulo `intelligence`

Dois listeners independentes reagindo ao mesmo evento, sem se conhecerem: `DiffSummaryListener` (só quando `isNewVersion=true` e não é a primeira versão) persiste em `page_diffs` (`UNIQUE (page_id, from_version, to_version)` — idempotente contra redelivery do evento); `AlertCheckListener` avalia regras ativas (`KEYWORD` via texto, `CATEGORY` via `PageContentLookup.currentCategory`, `ENTITY_TYPE` via `graph.GraphStore`) e persiste em `alerts` (`UNIQUE (rule_id, page_id, page_version)`, mesma razão).

## 4. Checklist de DoD (onionmind-sdd.md)

- [x] `EntityProcessor` extrai entidades e popula Neo4j — `EntityExtractionListener` + `Neo4jGraphStore`
- [x] Motor de diff detecta mudanças reais via `content_hash` — `DiffSummaryListener` só roda quando `isNewVersion=true`
- [x] Query "o que mudou desde ontem" retorna resumo correto — `GET /api/pages/diff?url=...`, provado com fake em `Fase4KnowledgeGraphGateTest`

## 5. Validação real pendente

Atualização de 2026-09-06: com Docker disponível no ambiente, a suíte completa do módulo `core`
foi rodada de ponta a ponta contra infraestrutura real (não só fakes/mocks) — Postgres, Kafka,
Redis e Neo4j via Testcontainers (`GenericContainer` pro Neo4j, já que `org.testcontainers:neo4j`
não existe pra Testcontainers 2.x). Resultado: `BUILD SUCCESSFUL`, todos os testes passando,
incluindo `Neo4jGraphStoreTest` (merge por business key + relação `CO_OCCURS_WITH` contra um
Neo4j real, não um fake), `EventPublicationDurabilityTest` (evento sobrevive a um restart
simulado), `EventPublicationHousekeepingJobTest`, `PageDiffRepositoryTest`, `AlertRuleRepositoryTest`
e o gate `Fase4KnowledgeGraphGateTest`. Duas rodadas intermediárias tiveram falhas em massa
(`IllegalStateException`/`NoClassDefFoundError` vindas de `DockerClientProviderStrategy`), mas
confirmadas como instabilidade do daemon Docker Desktop no Windows sob carga sustentada, não bugs
de código — resolvidas reiniciando o daemon. Dessa verificação real também saíram dois bugs
genuínos, encontrados e corrigidos: `PageRepository.insertNew()` e `AlertRuleRepository.create()`
usavam `Statement.RETURN_GENERATED_KEYS` sem nomear a coluna, e o driver JDBC do Postgres retorna
a linha inteira nesse caso (não só a PK) — `KeyHolder.getKey()` explodia com
`InvalidDataAccessApiUsageException` em todo insert novo. Corrigido nos dois lugares com
`connection.prepareStatement(sql, new String[]{"id"})`.

O que isso prova: o código funciona contra um motor de grafo Neo4j de verdade (protocolo Bolt,
merge Cypher, schema de nós/relações) e contra Postgres/Kafka/Redis reais — não é mais "só
fakes". O que **continua pendente**, e não foi coberto por essa rodada:

- Rodar `graph.enabled=true`/`intelligence.enabled=true` contra o Neo4j **AuraDB Free** de
  produção (a suíte usa um Neo4j containerizado local, não o serviço gerenciado) e confirmar
  merge por business key/co-occorrência com dados de páginas `.onion` reais, não sintéticos.
- Rodar `extractEntities`/`summarizeDiff` contra Ollama/Groq/Gemini reais (a suíte usa o
  `AIOrchestrator` fake) e registrar aqui data, tempo observado e distribuição de confidence, no
  mesmo padrão da Fase 1/3.
- Definir política de retenção pra `page_diffs`/`alerts` (fora de escopo desta fase — ver
  design.md, Risks/Trade-offs).
