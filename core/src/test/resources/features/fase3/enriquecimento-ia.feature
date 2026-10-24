# language: pt
Funcionalidade: Fase 3 — enriquecimento por IA
  Páginas novas ganham resumo em inglês, categoria e idioma, sem tradução, com o
  provedor de IA escolhido automaticamente. Conteúdo barrado vai para quarentena
  antes de qualquer modelo tocá-lo.

  Cenário: Página nova em português é enriquecida
    Dado que a página "http://forum-pt.onion/" foi publicada com o texto "Este fórum em português discute a privacidade e a troca de bens digitais entre os usuários que não revelam a identidade para mais ninguém."
    Quando o ciclo de enriquecimento roda
    Então a página "http://forum-pt.onion/" está com status de IA "processed"
    E a página "http://forum-pt.onion/" tem resumo, categoria e idioma preenchidos
    E o idioma da página "http://forum-pt.onion/" é "pt"

  Cenário: Página em português ganha resumo em inglês sem tradução
    Dado que a página "http://mercado-pt.onion/" foi publicada com o texto "Este mercado em português vende bens digitais e explica para os usuários como manter a privacidade e a identidade longe de todos que não querem ser vistos na rede."
    Quando o ciclo de enriquecimento roda
    Então a página "http://mercado-pt.onion/" está com status de IA "processed"
    E o idioma da página "http://mercado-pt.onion/" é "pt"
    E o modelo recebeu o pedido de resumo em inglês com o texto original da página "http://mercado-pt.onion/"
    E nenhuma tradução foi pedida ao modelo
    E o texto original da página "http://mercado-pt.onion/" continua contendo "Este mercado em português"

  Cenário: Categoria fora da taxonomia não é persistida
    Dado que o modelo de IA responde a categoria "receitas culinárias"
    E que a página "http://forum-pt.onion/" foi publicada com o texto "Este fórum em português discute a privacidade e a troca de bens digitais entre os usuários que não revelam a identidade para mais ninguém."
    Quando o ciclo de enriquecimento roda
    Então a categoria da página "http://forum-pt.onion/" pertence à taxonomia
    E a categoria da página "http://forum-pt.onion/" não é "receitas culinárias"

  Cenário: Host na denylist vai para quarentena
    Quando a página "http://proibido-aceitacao.onion/catalogo" é publicada com o texto "Conteúdo de um host que está na lista de bloqueio auditada do projeto."
    Então a página "http://proibido-aceitacao.onion/catalogo" está em quarentena sem texto nem HTML
    E a página "http://proibido-aceitacao.onion/catalogo" está com status de IA "quarantined"

  Cenário: Provedor preferido sem cota não interrompe o enriquecimento
    Dado que o provedor "groq" está sem cota no minuto corrente
    E que a página "http://artigo-longo.onion/" foi publicada com um texto longo em português
    Quando o ciclo de enriquecimento roda
    Então a página "http://artigo-longo.onion/" está com status de IA "processed"
    E o provedor "groq" não recebeu chamadas de geração
    E o provedor "gemini" recebeu chamadas de geração

  Cenário: Busca expõe o enriquecimento
    Dado que a página "http://forum-pt.onion/" foi publicada com o texto "Este fórum em português discute o xilofonista e a privacidade entre os usuários que não revelam a identidade para ninguém."
    E que o ciclo de enriquecimento rodou
    Quando eu busco por "xilofonista"
    Então o resultado da página "http://forum-pt.onion/" traz resumo e categoria

  Cenário: Página ainda pendente é retornada sem erro
    Dado que a página "http://forum-pt.onion/" foi publicada com o texto "Este fórum em português discute o xilofonista e a privacidade entre os usuários que não revelam a identidade para ninguém."
    Quando eu busco por "xilofonista"
    Então a resposta tem status 200
    E o resultado da página "http://forum-pt.onion/" vem sem resumo nem categoria
