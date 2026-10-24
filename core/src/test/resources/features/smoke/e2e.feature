# language: pt
@e2e
Funcionalidade: Ponta a ponta — crawler, Kafka, core, busca semântica, IA e grafo
  Roda contra a stack de e2e/docker-compose.e2e.yml (via scripts/e2e-smoke.sh): a imagem
  real do crawler coleta os sites .onion fake de crawler/cmd/e2e-fakes e o core indexa,
  embedda e enriquece. Termos e URLs abaixo vêm das páginas de e2e-fakes — mude os dois juntos.
  Fora do smoke padrão: rode com SMOKE_TAGS=@e2e.

  Cenário: Fase 1 — semente e página linkada ficam encontráveis
    Então em até 90 segundos a busca por "xilofonista" contém a página "http://semente-e2e.onion/"
    E em até 30 segundos a busca por "quetzal" contém a página "http://linkada-e2e.onion/"
    E em até 30 segundos o total de páginas é pelo menos 3

  Cenário: Fase 2 — busca semântica acha por conceito
    Então em até 60 segundos a busca semântica por "criptomoedas" contém a página "http://conceito-e2e.onion/"

  Cenário: Fase 3 — página coletada é enriquecida
    Então em até 60 segundos o resultado de "http://semente-e2e.onion/" na busca por "xilofonista" traz resumo e categoria

  Cenário: Fase 3 — host na denylist não é indexado
    Então em até 30 segundos a busca por "quetzal" contém a página "http://linkada-e2e.onion/"
    E a busca por "zircônio" não contém a página "http://proibido-e2e.onion/"

  Cenário: Fase 4 — carteira citada numa página coletada vira entidade no grafo
    Então em até 90 segundos a entidade "crypto_wallet" "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa" lista a página "http://linkada-e2e.onion/"
