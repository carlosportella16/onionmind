# language: pt
Funcionalidade: Fase 4 — grafo de entidades
  Carteiras, endereços onion e nomes citados nas páginas viram entidades num grafo,
  para perguntar quem mais cita uma carteira e o que aparece junto dela.

  Cenário: Carteira e pessoa citadas numa página viram entidades consultáveis
    Dado que a página "http://loja-a.onion/" foi publicada com o texto "Loja de livros raros. Pagamento somente em 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa, fale com Ana Souza."
    Quando o ciclo de enriquecimento roda
    Então a entidade "crypto_wallet" "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa" é citada pelas páginas "http://loja-a.onion/"
    E a entidade "person" "Ana Souza" é citada pelas páginas "http://loja-a.onion/"
    E a entidade "crypto_wallet" "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa" aparece junto de "person" "ana souza"

  Cenário: Mesma carteira em dois serviços é uma entidade só
    Dado que as páginas abaixo foram publicadas:
      | url                  | texto                                                                  |
      | http://loja-a.onion/ | Loja de livros raros. Pagamento em 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa. |
      | http://loja-b.onion/ | Outra loja, mesmo dono? Doações para 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa. |
    Quando o ciclo de enriquecimento roda
    Então a entidade "crypto_wallet" "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa" é citada pelas páginas "http://loja-a.onion/, http://loja-b.onion/"

  Cenário: Carteira com checksum inválido não vira entidade
    Dado que a página "http://loja-a.onion/" foi publicada com o texto "Pagamento em 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb, confie."
    Quando o ciclo de enriquecimento roda
    Então consultar a entidade "crypto_wallet" "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNb" responde 404
