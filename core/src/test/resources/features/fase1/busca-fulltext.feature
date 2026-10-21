# language: pt
Funcionalidade: Fase 1 — ingestão e busca full-text
  Páginas que chegam pelo tópico raw-pages são sanitizadas, versionadas
  e ficam encontráveis pela busca full-text.

  Cenário: Página ingerida é encontrada pela busca
    Quando a página "http://forum-alfa.onion/" é publicada com o texto "Este fórum discute o xilofonista misterioso entre os membros da comunidade."
    E eu busco por "xilofonista"
    Então a resposta tem status 200
    E os resultados contêm a página "http://forum-alfa.onion/"

  Cenário: Conteúdo alterado gera nova versão
    Dado que a página "http://forum-alfa.onion/" foi publicada com o texto "Primeira versão do conteúdo publicado no fórum alfa."
    Quando a página "http://forum-alfa.onion/" é publicada com o texto "Segunda versão, agora com um conteúdo diferente no fórum alfa."
    Então a página "http://forum-alfa.onion/" está na versão 2
    E existe 1 versão arquivada da página "http://forum-alfa.onion/"

  Cenário: Conteúdo idêntico não gera nova versão
    Dado que a página "http://forum-alfa.onion/" foi publicada com o texto "Conteúdo que não muda entre duas coletas do crawler."
    Quando a página "http://forum-alfa.onion/" é publicada com o texto "Conteúdo que não muda entre duas coletas do crawler."
    Então a página "http://forum-alfa.onion/" está na versão 1
    E existem 0 versões arquivadas da página "http://forum-alfa.onion/"

  Cenário: HTML perigoso não é persistido
    Quando a página "http://forum-alfa.onion/" é publicada com o HTML:
      """
      <html><body><p>Conteúdo legítimo sobre o quetzal para a busca encontrar.</p><script>roubarSessao('cookie-secreto')</script></body></html>
      """
    Então o texto persistido da página "http://forum-alfa.onion/" não contém "cookie-secreto"
    Quando eu busco por "quetzal"
    Então os resultados contêm a página "http://forum-alfa.onion/"
    E nenhum trecho dos resultados contém "cookie-secreto"

  Cenário: Estatísticas refletem a ingestão
    Dado que as páginas abaixo foram publicadas:
      | url                       | texto                                                  |
      | http://pagina-um.onion/   | Primeira página distinta coletada pelo crawler hoje.   |
      | http://pagina-dois.onion/ | Segunda página distinta coletada pelo crawler hoje.    |
      | http://pagina-tres.onion/ | Terceira página distinta coletada pelo crawler hoje.   |
    Quando eu consulto as estatísticas
    Então o total de páginas é 3

  Cenário: Consulta vazia é rejeitada
    Quando eu busco por ""
    Então a resposta tem status 400
