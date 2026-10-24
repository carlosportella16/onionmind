# language: pt
Funcionalidade: Fase 4 — o que mudou
  Cada nova versão de uma página é comparada com a última versão enriquecida. Mudanças
  reais ganham um resumo curto; contadores, datas e reordenação não contam como mudança.

  Contexto:
    Dado que as páginas abaixo foram publicadas:
      | url                          | texto                                                    |
      | http://forum-a.onion/        | Fórum de privacidade. Regras da casa: sem spam.          |
      | http://forum-contador.onion/ | Fórum de leitores. Visitas hoje: 1523.                   |
      | http://mercado-a.onion/      | Mercado de livros raros. Entrega discreta.               |
    E que o ciclo de enriquecimento rodou

  Cenário: O que mudou nos fóruns desde ontem
    Quando a página "http://forum-a.onion/" é publicada com o texto "Fórum de privacidade. Regras da casa: sem spam. Novo tópico: guia completo de carteiras Monero para iniciantes."
    E a página "http://forum-contador.onion/" é publicada com o texto "Fórum de leitores. Visitas hoje: 1524."
    E a página "http://mercado-a.onion/" é publicada com o texto "Mercado de livros raros. Entrega discreta. Agora aceitamos pagamento em 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa."
    E o ciclo de enriquecimento roda
    E o ciclo de resumo de mudanças roda
    E eu pergunto o que mudou na categoria "forum" desde ontem
    Então as mudanças listadas são somente das páginas "http://forum-a.onion/"
    E a mudança da página "http://forum-a.onion/" traz um resumo
    E a mudança da página "http://forum-a.onion/" lista o trecho adicionado "Novo tópico: guia completo de carteiras Monero para iniciantes."

  Cenário: Carteira nova num mercado aparece como entidade adicionada
    Quando a página "http://mercado-a.onion/" é publicada com o texto "Mercado de livros raros. Entrega discreta. Agora aceitamos pagamento em 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa."
    E o ciclo de enriquecimento roda
    E eu pergunto o que mudou na categoria "marketplace" desde ontem
    Então as mudanças listadas são somente das páginas "http://mercado-a.onion/"
    E a mudança da página "http://mercado-a.onion/" adiciona a entidade "crypto_wallet"

  Cenário: Mudança só de contador não aparece
    Quando a página "http://forum-contador.onion/" é publicada com o texto "Fórum de leitores. Visitas hoje: 1999."
    E o ciclo de enriquecimento roda
    E eu pergunto o que mudou na categoria "forum" desde ontem
    Então nenhuma mudança é listada

  Cenário: Página bloqueada sai do grafo e do histórico
    Dado que a página "http://forum-a.onion/" foi publicada com o texto "Fórum de privacidade. Regras da casa: sem spam. Contato: Ana Souza, que modera o novo fórum desde o início."
    E que o ciclo de enriquecimento rodou
    Quando a página "http://forum-a.onion/" é publicada com o texto "Fórum de privacidade. Agora com marcador-proibido-aceitacao."
    E os eventos foram entregues
    E eu pergunto o que mudou na categoria "forum" desde ontem
    Então nenhuma mudança é listada
    E consultar a entidade "person" "Ana Souza" responde 404
    E nenhuma versão arquivada da página "http://forum-a.onion/" guarda texto
