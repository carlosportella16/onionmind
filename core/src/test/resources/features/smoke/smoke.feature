# language: pt
@smoke
Funcionalidade: Smoke — instância em execução responde nos contratos principais
  Só requisições GET: seguro contra ambiente compartilhado ou produção.
  URL base em SMOKE_BASE_URL (default http://localhost:8081).

  Cenário: Aplicação saudável
    Quando eu consulto "/actuator/health"
    Então a resposta tem status 200
    E o campo "status" é "UP"

  Cenário: Busca full-text responde com o contrato
    Quando eu consulto "/api/search?q=onion"
    Então a resposta tem status 200
    E a resposta tem a lista "results" e os campos "total", "page" e "size"

  @semantic
  Cenário: Busca semântica responde
    Quando eu consulto "/api/search/semantic?q=onion"
    Então a resposta tem status 200
    E a resposta tem a lista "results"

  Cenário: Estatísticas
    Quando eu consulto "/api/stats"
    Então a resposta tem status 200
    E os campos "total_pages" e "total_versions" são numéricos

  @ia
  Cenário: Métricas de IA expostas
    Quando eu consulto "/actuator/prometheus"
    Então a resposta tem status 200
    E o corpo contém as séries "ingestion_ai_lag" e "ai_quota_remaining"

  @somente-leitura
  Cenário: Execução não altera dados
    Dado que eu anotei as estatísticas
    Quando eu consulto "/api/search?q=onion"
    E eu consulto "/api/stats"
    Então as estatísticas não mudaram
