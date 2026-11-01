# language: pt
Funcionalidade: Fase 2 — busca semântica
  Páginas são embeddadas pelo modelo local real e indexadas no Qdrant; a busca encontra
  por conceito, também de português para inglês, descarta o que está abaixo do piso de
  similaridade e não deixa uma página sozinha ocupar todos os resultados.

  Cenário: Conceito acha o que o full-text não acha
    Dado que a página "http://mercado-anonimo.onion/" foi publicada com o texto "Marketplace that accepts anonymous digital money and records every sale on a decentralized blockchain."
    E que o ciclo de enriquecimento rodou
    Então a busca full-text por "criptomoedas" não encontra a página "http://mercado-anonimo.onion/"
    Quando eu faço a busca semântica por "criptomoedas"
    Então os resultados contêm a página "http://mercado-anonimo.onion/"

  Cenário: Resultados abaixo do piso de similaridade são descartados
    Dado que a página "http://mercado-anonimo.onion/" foi publicada com o texto "Marketplace that accepts anonymous digital money and records every sale on a decentralized blockchain."
    E que o ciclo de enriquecimento rodou
    Quando eu faço a busca semântica por "receita de bolo de cenoura"
    Então a resposta tem status 200
    E os resultados vêm vazios

  Cenário: Uma página com muitos trechos não monopoliza os resultados
    Dado que a página "http://lista-de-links.onion/" foi publicada com o texto "bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero bitcoin blockchain monero"
    E que a página "http://guia-cripto.onion/" foi publicada com o texto "A short, honest guide to keeping your cryptocurrency safe at home."
    E que o ciclo de enriquecimento rodou
    E que a página "http://lista-de-links.onion/" tem mais de 2 trechos no índice vetorial
    Quando eu faço a busca semântica por "bitcoin" pedindo 2 resultados
    Então os resultados contêm a página "http://lista-de-links.onion/"
    E os resultados contêm a página "http://guia-cripto.onion/"
    E cada página aparece no máximo uma vez nos resultados

  Cenário: Busca híbrida combina full-text e semântica
    Dado que a página "http://catalogo-minerais.onion/" foi publicada com o texto "Catálogo de minerais raros com amostras de zircônio vindas de várias regiões."
    E que a página "http://mercado-anonimo.onion/" foi publicada com o texto "Marketplace that accepts anonymous digital money and records every sale on a decentralized blockchain."
    E que o ciclo de enriquecimento rodou
    Então a busca full-text por "zircônio or criptomoedas" não encontra a página "http://mercado-anonimo.onion/"
    Quando eu busco por "zircônio or criptomoedas"
    Então os resultados contêm a página "http://catalogo-minerais.onion/"
    E os resultados contêm a página "http://mercado-anonimo.onion/"
