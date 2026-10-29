# language: pt
Funcionalidade: Fase 5 — agentes
  Um agente é uma configuração nomeada: instruções, tema, filtros, ferramentas e teto de custo.
  Executá-lo coleta o contexto só das ferramentas ligadas e gera um relatório em inglês que cita
  as fontes. Usuário único: sem login.

  Contexto:
    Dado que a página "http://passaportes.onion/" foi publicada com o texto "Marketplace selling fake passports and forged identity documents. Contact: Ana Souza."
    E que o ciclo de enriquecimento rodou
    Quando a página "http://passaportes.onion/" é publicada com o texto "Marketplace selling fake passports and forged identity documents. Contact: Ana Souza. New this week: driving licences for every country, shipped in two days."
    E o ciclo de enriquecimento roda
    E o ciclo de resumo de mudanças roda

  Cenário: A base nova traz o Research Agent
    Quando eu listo os agentes
    Então a lista de agentes contém "Research Agent"

  Cenário: Criar e executar um agente com as três ferramentas
    Quando eu crio o agente "Passport watcher" sobre "passports" com as ferramentas "search,changes,entities" e teto de 8000 tokens
    Então a resposta tem status 201
    Quando eu listo os agentes
    Então a lista de agentes contém "Passport watcher"
    Quando eu executo o agente "Passport watcher"
    Então a execução termina "succeeded" com um relatório em inglês
    E as fontes da execução vêm das ferramentas "search,changes,entities"
    E o histórico do agente "Passport watcher" tem 1 execução

  Cenário: Teto menor que o prompt mínimo falha sem chamar o modelo
    Quando eu crio o agente "Penny pincher" sobre "passports" com as ferramentas "search,changes" e teto de 50 tokens
    E eu executo o agente "Penny pincher"
    Então a execução termina "failed" com o motivo "budget"
    E o modelo não recebeu nenhum pedido de relatório

  Cenário: Ferramenta desabilitada não é consultada
    Quando eu crio o agente "Search only" sobre "passports" com as ferramentas "search" e teto de 8000 tokens
    E eu executo o agente "Search only"
    Então a execução termina "succeeded" com um relatório em inglês
    E as fontes da execução vêm das ferramentas "search"
    E o modelo não recebeu nenhuma mudança como fonte

  Cenário: Ferramenta desconhecida é recusada nomeando a ferramenta
    Quando eu crio o agente "Curious" sobre "passports" com as ferramentas "search,telnet" e teto de 8000 tokens
    Então a resposta tem status 400
    E o erro do campo "tools" menciona "telnet"

  Cenário: Remover um agente remove o seu histórico
    Quando eu crio o agente "Short-lived" sobre "passports" com as ferramentas "search" e teto de 8000 tokens
    E eu executo o agente "Short-lived" 3 vezes
    Então o histórico do agente "Short-lived" tem 3 execuções
    Quando eu removo o agente "Short-lived"
    Então a resposta tem status 204
    E consultar o agente "Short-lived" responde 404
    E consultar as execuções do agente "Short-lived" responde 404
