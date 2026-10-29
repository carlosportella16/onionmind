# OnionMind — Fase 5: RAG + Agent Playground (SDD)

**Objetivo:** responder perguntas sobre a base com fontes citadas ("RAG pipeline funcional") e rodar agentes configuráveis sobre ela ("Agent Playground funcional").

**Status:** código dos três changes completo e testado (`phase5-rag-chat`, `phase5-agent-playground` e `local-onnx-embeddings`). Validação real feita em 2026-10-25 com DeepSeek e o banco de dev (seção "Validação real"); falta o benchmark na VPS (`local-onnx-embeddings` 6.1).

A seção "Chat (RAG)" vem da branch `feat/phase-5` (`phase5-rag-chat`); o merge junta as seções.

---

## Agentes (playground)

OpenSpec change `phase5-agent-playground`, branch `feat/phase5-agent-playground`. Segundo item do gate da Fase 5: "Agent Playground funcional".

### Modelo de agente

Um agente é configuração sobre o que já roda, sem código por agente (`mvp-architecture.md`, Fase 5). Fica no Postgres (`agents`, migration V10) e é gerido por `/api/agents`:

| Campo | Significado | Limite |
|---|---|---|
| `name` | único | 100 caracteres |
| `instructions` | texto livre enviado ao LLM com o tema | `agents.max-instructions-chars` (4000) |
| `topic` | a consulta de busca | 200 caracteres |
| `categories` | códigos de `content.Categories` que filtram as mudanças; vazio = todas | só a taxonomia |
| `windowDays` | até quando as mudanças voltam | 1 a 30 (o mesmo teto do `/api/changes`) |
| `tools` | subconjunto de `search`, `changes`, `entities` | pelo menos uma |
| `costProfile` | `interactive` ou `batch` | |
| `maxTokensPerRun` | teto de tokens de uma execução | 1 a 100000 |
| `daily` | roda uma vez por dia pelo agendador | |

Uma configuração recusada responde `{"errors": {campo: mensagem}}`: 400 quando inválida (a mensagem nomeia a ferramenta desconhecida), 409 quando o nome já existe. Remover um agente remove as execuções (`ON DELETE CASCADE`).

A migration cria um **Research Agent**: as três ferramentas, `batch`, `daily = false`, tema `bitcoin`, janela de 7 dias, teto de 8000 tokens.

### Interpretação de "subconjunto de processors" (design D1)

Os SDDs definem um agente como "uma composição nomeada de um subconjunto de processors". Os `ContentProcessor`s rodam na ingestão, por página, e não fazem sentido como passo de um agente que consulta a base. O conjunto fixo do agente são, então, **ferramentas de consulta**:

| Ferramenta | Ids das fontes | O que coleta |
|---|---|---|
| `search` | `S1`… | trechos sobre o tema pelo `PassageRetriever` do chat (full-text + vetorial, RRF), na ordem do RRF |
| `changes` | `C1`… | mudanças resumidas na janela e nas categorias, mais novas primeiro, até 20 (`intelligence.ChangeQuery`) |
| `entities` | `E1`… | as até 5 entidades mais citadas pelas páginas achadas para o tema, cada uma com as páginas que a citam e as coocorrências (`GraphRepository.mostMentionedIn` + `find`) |

A coleta é determinística; o LLM só escreve o relatório. Não há laço agêntico: o custo fica previsível e o comportamento, testável. A busca roda uma vez por execução e é compartilhada por `search` e `entities`; um agente só com `changes` não busca. As categorias filtram só as mudanças, como diz o D1.

### Execução

`POST /api/agents/{id}/runs` roda de forma síncrona e devolve a execução; `GET /api/agents/{id}/runs` lista as execuções, mais novas primeiro, e `GET /api/agents/{id}/runs/{runId}` devolve uma.

1. A execução é gravada como `running`.
2. As ferramentas ligadas coletam. `entities` sem grafo (`graph.enabled=false`) é pulada e registrada em `unavailableTools`; a execução segue.
3. Nada coletado → `succeeded` com "The indexed pages have no material on this topic in the period.", sem chamar o LLM.
4. **Teto (D3).** Estimativa de caracteres/4. O orçamento das fontes é `maxTokensPerRun − 1000 − instruções − tema`; os 1000 tokens reservados cobrem o prompt de sistema e o próprio relatório. As fontes entram em ordem de prioridade (search pelo RRF, changes mais novas primeiro, entities), e a que não cabe fica de fora. Nenhuma cabe → `failed` com motivo `budget`, sem chamar o LLM.
5. `AIOrchestrator.report` (`TaskType.REPORT`, prompt que começa com "Write a research report") escreve um relatório em inglês, com menos de 500 palavras, que cita os ids das fontes. As fontes vão em tags `<source id="…">`, como dado; ids que não foram enviados são descartados, e um relatório sem citação válida vira a frase de sem material. Perfil `interactive` → tarefa interativa (só a margem de segurança do orçamento); `batch` → margem de batch (`ai-token-quota`).
6. A execução é gravada `succeeded` com o relatório, as fontes **citadas** e `tokensUsed` (a estimativa caracteres/4 do que foi enviado mais o relatório).

Prazo (D5): `agents.interactive-timeout` (20 s) ou `agents.batch-timeout` (60 s), sobre coleta e geração. Passado o prazo, a execução é `failed/timeout`; uma falha do provedor é `failed/error`.

**Execuções diárias.** `DailyAgentJob` (`agents.daily.cron`, padrão 06:00 UTC, só com `ai.enabled`) roda todo agente com `daily = true`, sempre como batch. Um índice único parcial em `(agent_id, data UTC de started_at) WHERE trigger = 'daily'` faz um segundo disparo no mesmo dia não fazer nada, mesmo vindo de uma segunda instância.

### Usuário único

Decisão do usuário (2026-10-24): um usuário, sem identidade, sem login e sem compartilhamento. "Privado por usuário" vale porque só existe um usuário. As instruções são texto livre que chega ao LLM: aceitável para um usuário de confiança; o conteúdo das páginas continua delimitado como dado. As execuções são síncronas e seguram a requisição até 60 s, o que basta para um usuário; o playground mostra um estado de carregamento.

### Desvios do design

- `agent_runs.unavailable_tools` (fora do D4) registra uma ferramenta ligada, mas indisponível.
- O módulo `agents` depende de `search` (`PassageRetriever`) e de `ai` (`report`), e não de `chat`: o serviço do chat é interno, e esses dois são o que ele também usa.
- O índice diário usa a data UTC, porque `date(timestamptz)` não é imutável e não pode ser indexado.
- O teto reserva 1000 tokens para o prompt de sistema e o relatório, então o prompt mínimo é instruções + tema + uma fonte + 1000.

### Backlog

- **Multiusuário:** identidade, agentes por usuário, cotas por usuário.
- **Compartilhamento público de agentes e relatórios:** só com moderação (`sdd.md`, seção 9).
- **Laço agêntico:** um agente que planeja, escolhe ferramentas e itera; só se o relatório determinístico se mostrar insuficiente.
- Ferramentas definidas pelo usuário; execuções assíncronas com polling se os relatórios crescerem ou os usuários se multiplicarem.
- Uso real de tokens por execução, no lugar da estimativa caracteres/4.

---

## Embeddings locais (local-onnx-embeddings)

OpenSpec change `local-onnx-embeddings`, branch `feat/local-onnx-embeddings` (empilhada sobre `feat/phase5-agent-playground`).

### Por quê

A chave do Gemini disponível está no free tier: em 2026-10-25, os lotes de embedding voltaram HTTP 429 com `quotaId: EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier`. Texto `.onion` não pode ir para um free tier, que pode ser usado para treino e revisão humana. O DeepSeek, que virou o único provedor de geração, não tem API de embeddings (`/embeddings` responde 404). Os embeddings passaram a ser gerados no próprio core:

- `OnnxEmbedder` no módulo `ai`, no lugar do `GeminiEmbedder`: `multilingual-e5-small` int8 (`onnx/model_quantized.onnx` de `Xenova/multilingual-e5-small`, 118 MB), 384 dimensões, tokenizer SentencePiece (`sentencepiece.bpe.model` de `intfloat/multilingual-e5-small`, 5 MB), mean pooling e norma L2;
- prefixos `query: ` e `passage: ` no lugar dos do Gemini, no mesmo ponto do código;
- arquivos fora do repo: `scripts/fetch-embedding-model.sh` baixa de URLs fixas nas revisões `761b726` (Xenova) e `614241f` (intfloat) e confere o sha256; o core não sobe com `embedding.enabled` e um arquivo faltando;
- coleção nova `page_chunks_v3`, com `embedding.model: multilingual-e5-small-int8` no payload;
- `ai.ladder: deepseek` em todos os perfis, e um 429 com `quotaId` de free tier estaciona o provedor até liberação manual.

### Benchmark (2026-10-25, `scripts/embedding-bench.sh`, M4, Linux arm64 em Docker)

Um trecho por chamada, trechos sintéticos de 2048 caracteres:

| Modelo | Threads | Trechos/s | p50 por trecho | Pico de RSS |
|---|---|---|---|---|
| int8 | 1 | 7,8 | 128 ms | 402 MB |
| int8 | 2 | 14,4 | 70 ms | 403 MB |
| int8 | 4 | 22,9 | 42 ms | 407 MB |
| int8 | 8 | 20,9 | 47 ms | 416 MB |
| fp32 | 2 | 7,3 | 136 ms | 978 MB |
| fp32 | 4 | 12,3 | 77 ms | 983 MB |

- Embedar uma consulta: p95 de 1,5–3 ms.
- int8 contra fp32 em 50 trechos: cosseno médio 0,997, mínimo 0,995.
- Tokenizer: o HuggingFace (`tokenizer.json`, vocabulário de 250 mil tokens) ocupou ~575 MB de RSS só para carregar; o SentencePiece, ~126 MB.
- Lote com padding foi mais lento e mais pesado que um trecho por chamada.

### Decisões

- **D1, ONNX Runtime + SentencePiece da DJL** (`onnxruntime` 1.31.0, `ai.djl.sentencepiece` 0.39.0): os ids batem com a conversão do fairseq (id do SentencePiece + 1, `<s>`=0, `</s>`=2, `<unk>`=3 quando o SentencePiece devolve 0), e os dois jars trazem nativos para linux-x64, linux-aarch64 e osx-aarch64. O jar do core foi para 127 MB.
- **D2, `multilingual-e5-small` int8:** multilíngue (pergunta em português contra corpus em inglês), cabe na VPS e fica a 0,997 do fp32 com menos da metade da memória.
- **D4, CPU limitada:** uma sessão, criada na primeira chamada, com `embedding.threads` (2) threads; um lock justo por trecho, então uma consulta espera no máximo um trecho de uma página em backfill (`OnnxEmbedderTest` confere com um `embedAll` de 40 trechos longos).
- **D5, corte contado:** o modelo lê 510 tokens mais `<s>` e `</s>`; o resto é cortado e conta em `embedding_truncated_chunks_total`. O `chunk-size` foi calibrado no corpus real (seção "Validação real"): **300** tokens estimados (1200 caracteres), e não os 400 do design.
- **D7, guarda de free tier:** a leitura do `quotaId` saiu do `GeminiProvider` para o `HttpAIProvider`. Os ids vão na mensagem antes do corpo cortado (antes, o corte em 200 caracteres escondia o `quotaId`); `FreeTier` estaciona até liberação, `PerDay` até a virada do dia e o resto por um minuto. O DeepSeek não manda `quotaId`, então nada muda para ele.
- **D8, testes com o modelo real:** a aceitação e o e2e embedam com o modelo de verdade, e os fakes de embedding saíram. Os cenários semânticos usam páginas em inglês e perguntas em português, como no corpus real.

### Dimensionamento da VPS (D9)

Alvo: 4 vCPU, 8 GB de RAM e 100 GB de SSD, tudo num host.

| Serviço | Memória | Configuração |
|---|---|---|
| core | ~0,8–0,9 GB de RSS medido no re-embed e no chat (heap mais ~400 MB nativos do ONNX Runtime) | `-Xmx768m`, `mem_limit: 2g`, `embedding.threads: 2` |
| Neo4j | 512 MiB medidos no `docker stats` | heap 512m e pagecache 256m no `docker-compose.yml` |
| Postgres, Qdrant, Redpanda, Redis, Tor | ~0,1 + 0,35 + 0,25 + 0,01 + 0,07 GB medidos no dev | padrões, Redpanda `--memory=512M`, Redis `--maxmemory 128mb` |
| `redpanda-console` | — | perfil `tools`, fora do `up` padrão |

Total estimado de ~4–4,5 GB, com ~3,5 GB livres; o README recomenda 2 GB de swap. No disco, o `raw_html` cresce ~260 KB por página, ~200 mil páginas em 100 GB.

### Desvios do design

- `chunk-size` 300 em vez de 400 (com o 350 de reserva): a 350 o corte ainda era de 12%.
- `min-score` 0,79, mas ele quase não filtra no corpus real (seção "Validação real").
- O `application-sandbox.yml` não mudou: ele herda os blocos `embedding`, `qdrant` e `ai` do `application.yml`, e o teste do roteador carrega os três perfis.
- A aceitação mantém `ai.ladder=gemini,deepseek` para os cenários de escalonamento da Fase 3; a escada de fábrica é coberta pelo `CostOptimizerRouterTest`.
- O cenário de smoke "Métricas de IA expostas" passou a conferir `embedding_truncated_chunks_total` no lugar de `ai_quota_remaining`, que só existe para provedor com teto, e a escada só com o DeepSeek não tem nenhum.
- A métrica se chama `embedding.truncated.chunks.total` no Micrometer, como as outras do projeto, e sai como `embedding_truncated_chunks_total` no Prometheus.

---

## Validação real (2026-10-25)

Core da branch `feat/local-onnx-embeddings` (jar, `-Xmx768m`) contra a infra do compose de dev, com `ai.enabled`, `embedding.enabled` e `graph.enabled`. Só a chave do DeepSeek: `GEMINI_API_KEY` vazia e `ai.gemini.base-url` apontando para uma porta local fechada.

### Re-embed (`local-onnx-embeddings` 6.2)

| | chunk-size 400 | chunk-size 300 (final) |
|---|---|---|
| Páginas embedadas | 579 de 579 | 579 de 579 |
| Chunks na `page_chunks_v3` | 2526 | 3296 |
| Tempo, do boot ao último chunk | ~115 s | ~108 s (102 s do primeiro ao último) |
| Chunks cortados | 439 (17,4%) | 274 (8,3%) |

- Simulação com o mesmo tokenizer sobre o corpus: 42,5% de corte a 512, 17,4% a 400, 12,3% a 350, 8,3% a 300 e 7,7% a 250. O que ainda é cortado está concentrado em 17 páginas, a maioria listas de endereços `.onion` (listagens do Ahmia e listas de banidos), onde o fim do trecho não muda o sentido.
- RSS do core: ~0,8 GB estável durante o re-embed e pico de 866 MB; pico de 922 MB com o backfill de enriquecimento, o chat e o agente rodando.
- Busca, 100 requisições sequenciais (20 consultas × 5): `/api/search/semantic` com p50 de 18 ms e p95 de 35 ms; `/api/search` (híbrida) com p50 de 17 ms e p95 de 31 ms. A primeira consulta depois do boot levou 1,7 s, que é a carga da sessão do modelo.

### Calibração do `min-score`

Procedimento da P2-2 (`fase3-sdd`): as 10 perguntas da avaliação do chat (5 em português, 5 em inglês), cruas e reescritas como palavras-chave em inglês, mais 9 consultas fora do tema e sem sentido, top 20 de cada sobre os 3296 chunks.

- Os scores do e5 são comprimidos: p50 de 0,834 e p90 de 0,868 entre todos os top 20.
- A página esperada apareceu em 18 das 20 consultas, com score de 0,808 a 0,931, na posição 1 em 13 delas.
- Fora do tema: topo de 0,834 a 0,850. Sem sentido: 0,866–0,867, e "lorem ipsum" 0,90.
- **Nenhum piso separa ruído de acerto no corpus real.** A ordem é que separa, e o RRF com o full-text ajuda. O `min-score` ficou em **0,79**: mantém todo acerto achado e corta o que fica claramente abaixo, como uma consulta em português fora do tema contra páginas em inglês (~0,72–0,78), que é o cenário da aceitação.

### Backfill de enriquecimento pelo DeepSeek

Rodou durante a validação (lote de 20 a cada 60 s) e levou o corpus de 20 para 218 páginas `processed`; as outras 361 ficam `pending` para o próximo backfill. A amostra manual de 6 páginas enriquecidas nesta rodada (troca de cripto, três mercados, uma loja de transferências e um site de apostas) teve resumos fiéis ao texto, idioma certo e categorias coerentes; o site de apostas caiu em `other` com confiança 0,6. 1718 entidades em 190 páginas no grafo.

### Nenhuma chamada ao Gemini

- `ai_request_total` só tem `provider="deepseek"`, `cache` ou `none` em todas as execuções do core.
- Nenhuma linha com `gemini` ou `generativelanguage` nos logs.
- A chave do Gemini não foi passada ao core, e a URL do Gemini apontava para uma porta local fechada.

### Avaliação do chat (`phase5-rag-chat` 6.2)

`openspec/changes/phase5-rag-chat/eval/chat_eval.py` com as 10 perguntas, intervalo de 2 s, cache de IA limpo antes da rodada:

| Critério | Resultado |
|---|---|
| hit@8 ≥ 7/10 | **9/10** (só a P3, e-mail anônimo, não trouxe a página esperada) |
| Respostas em inglês | 10/10 |
| p95 de latência ≤ 12 s | **3,45 s** (máximo 3,45 s), 0 degradadas, 0 erros |
| Reserva interativa sem 429 | não se aplica: o DeepSeek não tem teto configurado nem gate de cota, então não há reserva a medir; nenhum 429 |

Conferência das citações à mão, contra o texto de cada trecho citado:
- P2, P4, E1, E2, E3, E4 e E5: toda afirmação está no trecho citado. A E4 diz, corretamente, que o segundo VPS citado não fala em cobrança por hora.
- P1 (DebConf 2006): as datas citadas estão no trecho (S2, em espanhol); o local não está, e a resposta diz isso (confiança baixa).
- P5 (passaportes falsos): 8 fontes; a descrição de cada uma confere, menos "driver's licenses" atribuído à S1, que não aparece nesse trecho.
- P3: "The indexed pages do not contain an answer to this question", sem citação, coerente com a página esperada fora do top 8.

Uma primeira rodada, com o cache frio, teve uma resposta com corpo vazio na E3, que não se repetiu (nem num `curl` logo depois, nem nas duas rodadas seguintes).

### Research Agent (`phase5-agent-playground` 4.4)

O Research Agent da semente (tema `bitcoin`, as três ferramentas, `batch`, teto de 8000 tokens), executado com `POST /api/agents/1/runs`:

- `succeeded` em 4,9 s, com `tokensUsed` 7548, dentro do teto de 8000;
- relatório em inglês com 20 fontes citadas: 7 trechos (S), 9 mudanças (C) e 4 entidades (E);
- citações conferidas à mão, umas 30 afirmações contra o texto das fontes, e todas batem. Por exemplo: o mercado de chaves privadas com saldos de 0,07–8,38 BTC [S1]; as seeds a 0,08708 e 0,25631 BTC [S4]; os mixers e o "VirginBitcoins" [S8]; o e-mail secretbtc11@dnmx.cc com Blockchain.com, Exodus e Monero [E2–E4]; a política de privacidade do Ahmia de maio de 2025 a setembro de 2026 [C11]; Skrill, Visa e Mastercard nas mudanças agrupadas [C2][C3][C6]. As citações agrupadas são verdadeiras para o grupo, não para cada id.

### Pendente

- `local-onnx-embeddings` 6.1: benchmark na VPS (`FP32=1 bash scripts/embedding-bench.sh`) e ajuste de `embedding.threads`, se 2 não for o melhor custo-benefício.
- `local-onnx-embeddings` 3.2: a execução dos workflows no PR e o acerto do cache na segunda execução.
- Apagar a `page_chunks_v2` à mão (`curl -X DELETE http://localhost:6333/collections/page_chunks_v2`), agora que a avaliação do chat passou.
