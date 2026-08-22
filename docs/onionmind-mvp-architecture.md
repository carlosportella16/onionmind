# OnionMind — Arquitetura MVP-First e Boas Práticas (v2)

*An AI-powered Knowledge Discovery Platform, initially focused on the Tor Network.*

## TL;DR

- **Comece pequeno.** A Fase 1 é só: Go crawler → Redpanda → monolito Java modular → PostgreSQL (full-text) → busca. Sem IA, sem Neo4j, sem Qdrant. O objetivo dessa fase é provar que você consegue descobrir, coletar e indexar — nada mais.
- **Monolito modular, não 5 microsserviços.** Use Spring Modulith para dividir o Java em módulos internos com fronteiras impostas em build (`ingestion`, `content`, `search`). Extraia serviços separados só quando uma necessidade real de escala ou de ritmo de deploy aparecer — não antes.
- **Desenhe duas interfaces já na Fase 0**, mesmo vazias: `ContentProcessor` (pipeline de plugins) e `AIOrchestrator` (abstração sobre provedores de IA). Isso evita retrabalho quando a IA entrar na Fase 3 e desacopla o projeto do Spring AI — ele vira só um adapter, não uma dependência estrutural.
- **LLM por fase, sem custo.** Nada de geração de texto nas Fases 1–2 (embeddings locais via Ollama). Fase 3 introduz um Cost Optimizer simples que roteia entre Ollama (local, grátis, sem rate limit), Groq (grátis, latência baixíssima) e Gemini Flash (grátis, contexto de 1M tokens) — escalando para o próximo da escada só quando a confiança do resultado for baixa.
- **Deploy a custo zero.** Oracle Cloud Always Free (VM Ampere A1, roda tudo via Docker Compose, incluindo Ollama) + Qdrant Cloud free tier + Neo4j AuraDB Free + Cloudflare Pages para o frontend. Com ressalvas reais de capacidade regional e política de "idle reclamation" da Oracle.
- **Busca é só a primeira capacidade, não o produto todo.** Quatro personas (pesquisador, jornalista, analista de segurança, universidade — seção 10) orientam qual capacidade da Fase 5 vale priorizar primeiro.

## 1. Por que repensar a escala do projeto

A arquitetura original descreve uma plataforma completa: cinco bounded contexts como microsserviços, Kafka com Schema Registry, um sistema multiagente com dez agentes nomeados, knowledge graph, RAG com re-ranking, observabilidade full-stack. Tecnicamente, cada peça está certa — é o que times de plataformas de dados reais constroem. Mas para uma pessoa (ou uma dupla) construindo isso fora do horário integral, o volume de escopo *antes de qualquer entrega visível* é o maior risco do projeto — maior que a dificuldade técnica de qualquer peça isolada.

A correção não é reduzir a ambição da arquitetura final — é sequenciá-la. Cada fase abaixo entrega algo que funciona sozinho e é demonstrável. A arquitetura de cada fase é um subconjunto estrito da anterior mais uma camada nova; nada é descartado entre fases.

## 2. Monolito modular hoje, microsserviços quando doer

A decisão de maior impacto para reduzir risco de escopo é não abrir cinco microsserviços Java desde o início. Um **monolito modular** — um único artefato deployável, organizado internamente em módulos com fronteiras de código explícitas — dá quase todos os benefícios de organização dos microsserviços (separação de responsabilidades, testabilidade, possibilidade de extração futura) sem os custos operacionais deles (múltiplos pipelines de deploy, tracing distribuído entre processos, versionamento de contrato entre serviços).

Use o **Spring Modulith** para isso. Ele permite declarar módulos dentro do mesmo projeto Spring Boot (`ingestion`, `content`, `search`, e mais tarde `intelligence`, `graph`) e falha o build/teste se um módulo importar classes internas de outro sem passar pela API pública declarada. Isso significa que, quando a Fase 5 exigir extrair o módulo de busca para um serviço separado por um motivo real de escala, a extração é quase mecânica — o módulo nunca teve acoplamento escondido com o resto do sistema, porque o Spring Modulith proibiu isso durante todo o desenvolvimento.

Critério objetivo para decidir extrair um módulo: **(a)** ele precisa escalar horizontalmente de forma independente do resto (ex.: o Search sob alta carga de busca, enquanto Ingestion está ocioso), ou **(b)** o ciclo de deploy de um módulo está bloqueando o outro (ex.: mudanças frequentes em Intelligence forçam redeploy de tudo). Até que um desses dois sinais apareça de verdade, mantenha tudo em um só binário.

## 3. Roadmap por fases

### Fase 0 — Fundação (semanas 1–2)

Ambiente de desenvolvimento pronto, nenhuma lógica de negócio ainda.

Entregas: repositório com CI básico (lint + testes); `docker-compose.yml` local com Redpanda e PostgreSQL; schema inicial do Postgres já incluindo as colunas de versionamento (`content_hash`, `first_seen_at`, `last_seen_at`, `version`) e um campo `confidence` (JSON, nullable) em qualquer tabela que um dia vai receber saída de IA — mesmo vazias por semanas, essas colunas custam zero para desenhar agora e evitam uma migration dolorosa depois; as interfaces `ContentProcessor` e `AIOrchestrator` (seção 4) criadas com uma implementação no-op.

**Gate de saída:** `docker-compose up` sobe o ambiente completo localmente e os testes de integração (Testcontainers) passam.

### Fase 1 — Crawler + Busca Textual — o MVP real (semanas 2–6)

Provar a ponta a ponta descoberta → coleta → indexação → busca, sem nenhuma camada de IA.

Escopo: crawler Go (worker pool, SOCKS5h contra um pool de daemons Tor, circuit breaker via `gobreaker`, dedup via Redis) publica páginas cruas em um tópico Redpanda. Um consumer dentro do monolito Java sanitiza o HTML (OWASP Java HTML Sanitizer) e grava o texto extraído no PostgreSQL usando busca textual nativa (`tsvector`/`tsquery` com índice GIN). Uma API REST simples expõe busca por palavra-chave; um React SPA mínimo consome essa API.

Detalhe barato de preservar agora: nomeie o crawler como `TorConnector`, implementando uma interface genérica `SourceConnector`, e inclua um campo `source_type` no schema do evento publicado no Redpanda. Isso não muda nada do escopo da Fase 1 — só evita que "crawler" fique hardcoded como sinônimo de Tor no código, deixando a porta aberta para outros conectores mais tarde sem retrabalho de schema (seção 12).

Explicitamente fora de escopo nesta fase: embeddings, Qdrant, Neo4j, qualquer chamada a LLM, tradução, sumarização. Isso é intencional — o valor de uma busca textual funcionando de ponta a ponta, mesmo sem IA, já é maior do que qualquer peça de IA isolada sem um pipeline de dados real por trás.

**Gate de saída:** descobrir um novo `.onion`, indexá-lo e encontrá-lo por busca textual em minutos, de forma repetível e sem intervenção manual.

### Fase 2 — Busca Semântica (semanas 6–9)

Sair de "contém a palavra X" para "é sobre o assunto X".

Escopo: implementação do `EmbeddingProcessor` — primeira implementação real da interface `ContentProcessor` — usando um modelo leve local via Ollama (`nomic-embed-text`, ~274MB, roda em CPU) ou, como alternativa sem infraestrutura própria, o Cloudflare Workers AI (modelos BGE disponíveis no tier gratuito). Introdução do Qdrant (self-hospedado no mesmo host, ou tier gratuito do Qdrant Cloud — 1GB RAM / 4GB de disco, suficiente para centenas de milhares de vetores dependendo da dimensão escolhida). A busca passa a combinar full-text (Fase 1) e similaridade vetorial com uma fusão simples — mesmo sem RRF formal ainda, um round-robin já é uma melhoria real sobre busca puramente textual.

Ainda fora de escopo: qualquer geração de texto via LLM. Fase 2 é sobre representação, não sobre geração.

**Gate de saída:** uma busca por um conceito (não uma palavra exata) retorna resultados relevantes que a busca textual da Fase 1 não encontrava.

### Fase 3 — IA Generativa + Cost Optimizer (semanas 9–13)

Adicionar entendimento gerado por LLM sem acoplar o sistema a um provedor específico nem a uma conta paga.

Escopo: implementação real do `AIOrchestrator` (seção 4.2) com três adapters — Ollama local (grátis, sem rate limit, mas lento e limitado pelo hardware), Groq (grátis, latência baixíssima, mas com teto diário de requisições) e Gemini Flash (grátis, contexto de 1M tokens, mas limite de requisições por dia mais apertado). Implementação dos processors que dependem de IA: `TranslationProcessor`, `SummarizerProcessor`, `ClassifierProcessor`. Introdução do **AI Cost Optimizer** (seção 4.3) — nesta fase, um conjunto de regras simples, não um sistema de ML. Cada saída de IA grava um `confidence` no banco.

**Gate de saída:** uma página nova é automaticamente resumida, classificada e (se necessário) traduzida dentro de alguns minutos após ser indexada, com o provedor de IA escolhido automaticamente e sem estourar nenhum limite gratuito sob uso normal.

### Fase 4 — Knowledge Graph + Versionamento (semanas 13–17)

Relacionar entidades entre si e responder "o que mudou".

Escopo: `EntityProcessor` (spaCy/GLiNER para extração barata, escalonando para o LLM apenas em casos ambíguos). Neo4j — AuraDB Free (200 mil nós / 400 mil relações é generoso para os primeiros meses); self-hospedar Neo4j Community Edition no mesmo host se esse teto for atingido antes do esperado. O motor de diff usa o `content_hash` desenhado desde a Fase 0 para detectar mudança real de conteúdo (não apenas timestamp), e o LLM já disponível desde a Fase 3 é reaproveitado para gerar um resumo em linguagem natural do que mudou entre duas versões.

**Gate de saída:** perguntar "o que mudou nesta categoria desde ontem" retorna uma resposta correta e curta, não um dump de diffs brutos.

### Fase 5 — RAG Completo + Multiagentes (semanas 17+)

A experiência completa da visão original: chat conversacional sobre toda a base, agentes especializados, relatórios automáticos.

Escopo: pipeline RAG completo (query rewriting, retrieval híbrido com Reciprocal Rank Fusion, re-ranking via cross-encoder) sobre a infraestrutura já construída — nada aqui é componente novo, é composição do que já existe. **Agent Playground**: cada "agente" (Threat Agent, Research Agent, Translation Agent) é uma composição nomeada de um subconjunto de processors + um prompt especializado + um perfil de custo — não um novo sistema, uma camada de configuração sobre o que já roda. É também neste momento, e só neste momento, que vale reavaliar se algum módulo do monolito precisa virar um serviço separado (critério da seção 2). Mantenha essas configurações privadas por usuário nesta fase — transformar o Playground em algo compartilhável publicamente entre usuários introduz um risco específico do domínio (seção 5) e não é um requisito do MVP.

## 4. Padrões de design centrais

### 4.1 `ContentProcessor` — pipeline de plugins

Em vez de um pipeline de IA hardcoded (linguagem → tradução → classificação → entidades → embedding, tudo em uma função gigante), cada etapa implementa uma interface pequena:

```java
public interface ContentProcessor {
    ProcessingResult process(Document document);
    boolean supports(DocumentType type);
    default int order() { return 0; }
}
```

`ProcessingResult` carrega o documento resultante junto com metadados de execução (sucesso/falha, tempo gasto, erro) sem poluir o modelo de `Document` em si — isso dá métricas por etapa de graça. `order()` tem um default no código, mas a ordem efetiva do pipeline deve poder ser sobrescrita por uma propriedade de configuração (YAML/properties): só o valor fixo no código não permite desligar ou reordenar uma etapa sem recompilar, que é exatamente o ganho operacional que essa interface deveria trazer. O orquestrador do pipeline itera sobre a lista resultante de processors que retornam `supports() == true` para o tipo do documento (HTML, PDF, imagem). Adicionar um `OCRProcessor` para imagens ou um `PDFParserProcessor` mais tarde não toca em nenhum processor existente — só se registra na lista. Isso é o que torna o roadmap de fases seguro: cada fase adiciona processors novos sem reescrever os antigos.

### 4.2 `AIOrchestrator` — abstração sobre provedores de IA

Nenhum processor que precisa de IA chama Spring AI, Ollama ou Groq diretamente. Todos chamam uma interface própria:

```java
public interface AIOrchestrator {
    Summary summarize(String text, TaskContext ctx);
    Embedding embed(String text, TaskContext ctx);
    List<Entity> extractEntities(String text, TaskContext ctx);
    String translate(String text, String targetLang, TaskContext ctx);
}
```

O `TaskContext` carrega sinais que o Cost Optimizer usa para decidir (tamanho do texto, idioma, se é uma tarefa crítica, se uma tentativa anterior teve confiança baixa). A implementação concreta dessa interface — hoje usando Spring AI por baixo — é só um adapter. Se amanhã o Spring AI exigir uma migração incompatível com o resto do projeto, ou se outra biblioteca se mostrar melhor para um caso específico, a troca fica isolada em um único adapter, sem tocar em nenhum dos processors que consomem a interface.

Internamente, essa implementação não deveria ser uma função única e crescente — vale compor a cadeia via **Decorator**: um `MetricsDecorator` envolve um `CacheDecorator`, que envolve um `RetryDecorator` (backoff exponencial), que chama o Cost Optimizer para escolher o adapter (Ollama/Groq/Gemini), e a resposta passa por um `ValidationDecorator` antes de voltar ao processor. Isso permite adicionar cache, retry, métricas ou uma nova validação sem tocar na interface pública nem nos processors que a consomem — cada decorator é uma classe pequena e testável isoladamente.

### 4.3 AI Cost Optimizer — roteamento por custo e complexidade

Na Fase 3, isso é deliberadamente simples — um conjunto de regras, não um classificador treinado:

- Texto curto (< 500 tokens), tarefa de classificação simples → **Ollama local** (grátis, sem rate limit, latência aceitável para processamento em batch).
- Texto médio/longo, ou tarefa que precisa de qualidade melhor (resumo de página densa, tradução) → **Groq** (`llama-3.3-70b`, grátis, latência baixa) como primeira opção; se o teto diário de requisições gratuitas do Groq for atingido, cai para **Gemini Flash** (grátis, ~1.500 requisições/dia, contexto de 1M tokens).
- Qualquer saída com `confidence` abaixo de um limiar (ex.: 0.7) é reprocessada uma vez com o próximo modelo da escada — essa é a única parte "inteligente" do otimizador no MVP, e já captura a maior parte do valor de um roteador mais sofisticado sem precisar de um.

Evoluir isso para um roteador aprendido (que decide com base em desempenho histórico por tipo de conteúdo) é uma otimização de Fase 5+, não um requisito de MVP.

### 4.4 Confidence Score

Todo campo gerado por IA e persistido — resumo, categoria, idioma, entidade — carrega um valor de confiança ao lado do valor em si (mesmo schema JSON, ex.: `{"summary": "...", "confidence": 0.91}`). Isso serve três propósitos: alimenta o Cost Optimizer (reprocessar o que tem confiança baixa); permite auditoria (mostrar ao usuário o quão confiável é cada informação exibida); e vira, sem esforço extra, uma métrica de qualidade do pipeline ao longo do tempo. O mesmo caminho de reprocessamento vale para falhas de validação estrutural — se uma extração que deveria retornar JSON vem malformada, trate isso como confidence 0 e reprocesse pelo `ValidationDecorator` (seção 4.2), sem criar um segundo sistema de retry.

### 4.5 Versionamento e diff

Cada documento carrega `content_hash` (hash do conteúdo normalizado, não do HTML bruto — remova boilerplate antes de hashear), `first_seen_at`, `last_seen_at` e um contador de `version`. Quando um re-crawl encontra um hash diferente do último salvo, incrementa a versão e guarda o conteúdo anterior. A partir da Fase 4, um diff estrutural entre duas versões (quais entidades apareceram/sumiram, o que mudou na classificação) é sumarizado pelo LLM já disponível desde a Fase 3 — sem componente novo, é composição do que já existe.

## 5. Segurança por fase

Os fundamentos de segurança não esperam a Fase 5 — são não-negociáveis desde a Fase 1, mas o nível de sofisticação cresce com o roadmap.

**Fase 1 (mínimo viável de segurança):** todo HTML sanitizado antes de qualquer persistência (OWASP Java HTML Sanitizer, allowlist estrita — nunca blacklist); o crawler nunca fala diretamente com o Postgres, só publica no Redpanda; o container do crawler roda sem privilégios (non-root, capabilities dropped, filesystem read-only), e o egress do host é limitado ao proxy Tor e ao broker de eventos.

**Fase 3+ (quando IA processa conteúdo bruto pela primeira vez):** filtro de conteúdo ilegal (hashing perceptual contra bases conhecidas) precisa rodar *antes* de qualquer resumo ou classificação por LLM ser gerado — nunca deixe um LLM processar conteúdo que ainda não passou pelo filtro. Este é o único ponto do roadmap sem atalho de MVP: a política de descarte automático de conteúdo ilegal, com apenas metadados mínimos retidos para auditoria, precisa existir antes de qualquer processamento de IA em produção pública, mesmo que o resto do pipeline ainda seja simples.

**Fase 4+ (isolamento de rede mais rigoroso):** se o volume justificar mover os crawlers para um cluster Kubernetes, aplique NetworkPolicy default-deny e sandboxing (gVisor) — resposta a escala real, não pré-requisito do MVP rodando em uma única VM.

**Fase 5+ (configurações de agente):** se o Agent Playground algum dia permitir compartilhar configurações entre usuários, trate a configuração de um agente como conteúdo a ser revisado, não como metadado inofensivo — uma combinação de palavras-chave e filtros pode funcionar como receita de busca para exatamente as categorias de conteúdo que o filtro da Fase 3 existe para impedir. Compartilhar apenas templates genéricos, sem os parâmetros customizados de um usuário específico, é o caminho mais seguro até existir um processo de moderação para configurações públicas.

## 6. Event backbone: Redpanda em vez de Kafka completo

Para uma pessoa ou equipe pequena, rodar Kafka + Zookeeper (ou até Kafka em modo KRaft) já é uma fatia de complexidade operacional considerável antes de qualquer linha de lógica de negócio. **Redpanda** implementa o mesmo protocolo de rede do Kafka (qualquer cliente Kafka, em Java ou Go, funciona sem alteração), mas roda como um único binário, sem Zookeeper, com footprint de memória muito menor — perfeito para caber ao lado de tudo mais em uma única VM gratuita. A topologia de tópicos (`raw-pages`, `content-sanitized`, `content-enriched`, tópicos de retry/DLQ) se aplica sem alteração; o que muda é só o motor por trás do protocolo. Migrar de Redpanda para Kafka gerenciado mais tarde é uma troca de configuração de broker, não uma reescrita — os dois falam o mesmo protocolo.

## 7. Observabilidade mínima viável

**Fase 1:** logs estruturados em JSON (Logback no Java, `slog` no Go) e um endpoint de health check por serviço já resolvem a maior parte das necessidades de debug de um MVP solo.

**Fase 3+:** conforme o pipeline de IA e o volume de eventos crescem, adicione métricas via Micrometer → Prometheus (consumer lag do Redpanda é a métrica mais importante) e um dashboard simples no Grafana. Tracing distribuído completo (OpenTelemetry com propagação via headers do Kafka) só compensa o esforço quando há múltiplos serviços de fato — em um monolito modular, um bom log estruturado com um `correlation_id` por documento processado já dá a maior parte do valor de um tracing completo, por uma fração do esforço de configuração.

## 8. Estratégia de LLM gratuito por fase

| Fase | Uso de LLM | Provedor recomendado | Por quê |
|---|---|---|---|
| 1–2 | Nenhum (embeddings apenas) | Ollama local (`nomic-embed-text`) ou Cloudflare Workers AI | Sem geração de texto ainda; embeddings locais são gratuitos e ilimitados |
| 3 | Resumo, classificação, tradução em batch | Ollama local (padrão) → Groq (fallback de qualidade) → Gemini Flash (fallback de volume/contexto grande) | Cobre o consumo previsível do pipeline sem custo e sem depender de um único provedor |
| 5 | Chat interativo com o usuário (RAG) | Gemini Flash (~1.500 req/dia, 1M tokens de contexto, sem cartão) como principal; Groq como opção de baixíssima latência | Tráfego voltado ao usuário final precisa de resposta rápida e de um limite diário compatível com uso público de baixo/médio volume |

Tiers gratuitos de LLM mudam de mês a mês — os números acima refletem o estado observado em julho de 2026 e devem ser reconferidos antes de dimensionar qualquer limite de produção em torno deles.

## 9. Hospedagem a custo zero para uma app pública

- **Computação (crawler Go + monolito Java + Redpanda + Ollama):** uma única VM no **Oracle Cloud Always Free** (instâncias Ampere A1, atualmente 2 OCPUs / 12GB de RAM combinados, reduzido de 4/24GB em junho de 2026) roda todo o `docker-compose` do MVP, incluindo o Ollama para inferência local ilimitada. É hoje a única opção de VM sempre-gratuita e sempre-ligada que sobrevive nas comparações de 2026 — Fly.io e Heroku descontinuaram seus tiers gratuitos. Ressalva real: a disponibilidade de capacidade para novas contas Always Free varia por região, e a Oracle reclama instâncias ociosas — um cron leve mantendo alguma atividade evita isso. Se a aprovação do tier gratuito não for possível na sua região, uma VPS pequena paga (algo como uma Hetzner CX22, poucos euros por mês) é o fallback mais barato.
- **PostgreSQL:** rodar no mesmo container da VM Oracle elimina qualquer teto de armazenamento e latência de rede extra nas Fases 1–3. Quando quiser backups gerenciados e branching, Neon (10 projetos, 0.5GB por branch) ou Supabase (500MB, inclui auth/storage) têm tiers gratuitos permanentes — diferente do tier gratuito da Render, que é apagado após 30 dias.
- **Qdrant:** self-hospedado na mesma VM na Fase 2 (sem custo, sem tráfego de rede externo); migrar para o tier gratuito do Qdrant Cloud (1GB RAM / 4GB de disco) quando quiser separar o operacional de busca vetorial do resto.
- **Neo4j:** AuraDB Free (200 mil nós / 400 mil relações) para a Fase 4 — teto generoso para os primeiros meses de um knowledge graph; se atingido antes do esperado, Neo4j Community Edition self-hospedado na mesma VM não tem limite de tamanho (mas perde os recursos gerenciados do Aura).
- **Frontend:** Cloudflare Pages (hospedagem estática gratuita, sem teto de banda relevante para um MVP) para o React SPA.

## 10. Personas: quem abre isso todos os dias

A pergunta que orienta o que priorizar dentro de cada fase — principalmente na Fase 5, onde caberia qualquer agente: quem usa isso, e para quê?

- **Pesquisador** — quer acompanhar um tema ao longo do tempo, não fazer uma busca pontual. Prioriza a Fase 4 (diff/versionamento) e um Research Agent na Fase 5.
- **Jornalista** — quer descobrir mudanças relevantes sem revisitar manualmente centenas de páginas. Mesmas prioridades do pesquisador, com tolerância maior a latência em troca de profundidade no resumo.
- **Analista de segurança** — quer monitorar grupos ou tópicos específicos e ser avisado rápido. Prioriza alertas quase em tempo real, o que pesa mais na escolha do Cost Optimizer (latência acima de custo) do que nas outras personas.
- **Pesquisa acadêmica** — quer reconstruir o histórico de um tema ao longo de meses. Depende mais do knowledge graph (Fase 4) do que de alertas em tempo real.

Nenhuma persona aqui exige um componente novo — a lista serve para decidir, dentro do que a Fase 5 já entrega, qual agente construir primeiro.

## 11. User Journey

Um pesquisador digita "criptomoedas" na busca (Fase 1: já funciona por palavra-chave). O sistema encontra páginas relacionadas mesmo quando usam termos diferentes, como "moeda digital" ou "blockchain anônimo" (Fase 2: busca semântica). Cada resultado já vem com um resumo gerado automaticamente e uma categoria (Fase 3), então o pesquisador não precisa abrir cada página manualmente. Dias depois, ele volta e pergunta "o que mudou nesses fóruns desde a semana passada" e recebe um resumo das mudanças reais, não uma lista de páginas para revisitar uma por uma (Fase 4). Se o interesse dele for mais amplo — não uma busca pontual, mas acompanhamento contínuo de um tema — ele configura um "Research Agent" que monitora o assunto e manda um relatório periódico (Fase 5).

Cada fase do roadmap corresponde a um degrau real e visível dessa jornada — nenhuma fase existe só por completude arquitetural.

## 12. Backlog: Fase 6 e o que já preparamos de graça para ela

A visão de longo prazo do documento original — indexar não só a rede Tor, mas RSS, GitHub, PDFs e outras fontes, com a Tor virando só um dos conectores — está correta, e o desenho orientado a eventos já escolhido desde a Fase 1 torna isso barato de preservar sem construir nada agora. Como todo conector publica no mesmo tópico Redpanda antes de qualquer processamento acontecer, um conector novo (RSS, PDF, GitHub) é, tecnicamente, só mais um publisher no mesmo schema — não uma reestruturação do pipeline.

O único ajuste que vale fazer agora, de graça, para preservar essa opção já está descrito na seção 3 (Fase 1): nomear o crawler como `TorConnector` implementando uma interface genérica `SourceConnector`, e incluir um campo `source_type` no schema do evento. Isso não adianta nenhum trabalho de conectores futuros — só evita que o nome "crawler" fique hardcoded como sinônimo de Tor no código.

Mantenha isso deliberadamente como backlog, não como uma fase numerada com o mesmo peso das Fases 1–5 — o valor de sequenciar o roadmap depende de não tratar toda ideia boa como compromisso de entrega.

## Ressalvas

- Limites de tier gratuito (LLMs, hospedagem, bancos gerenciados) mudam com frequência; os números citados aqui são de julho de 2026 e precisam ser reconferidos periodicamente, especialmente antes de qualquer lançamento público.
- O roadmap assume desenvolvimento solo ou em dupla, fora do horário integral — os intervalos de semanas são uma referência de esforço relativo entre fases, não um compromisso de prazo.
- A extração de módulos do monolito para microsserviços (Fase 5+) deve ser guiada por necessidade real de escala ou de ritmo de deploy, não por preferência arquitetural — extrair cedo demais reintroduz o mesmo risco de escopo que este documento tenta evitar.
