# language: pt
Funcionalidade: Falhas isoladas
  Uma página que não pode ser publicada ou um host com erro não param a coleta.

  Cenário: Página maior que o teto de publicação
    Dado os sites onion abaixo:
      | url                   | html                                                                                               | tamanho_mb |
      | http://semente.onion/ | <html><body><a href="http://grande.onion/">g</a> <a href="http://beta.onion/">b</a></body></html> |            |
      | http://grande.onion/  | <html><body><p>enorme</p></body></html>                                                            | 2          |
      | http://beta.onion/    | <html><body><p>beta</p></body></html>                                                              |            |
    E o teto de publicação é de 1 MB
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 1
    Então o Redis tem um registro de página grande com a URL "http://grande.onion/" e o tamanho
    E a página "http://beta.onion/" é publicada
    E a página "http://grande.onion/" não é publicada

  Cenário: Host com erro não impede os demais
    Dado os sites onion abaixo:
      | url                   | html                                                                                               | status |
      | http://semente.onion/ | <html><body><a href="http://falha.onion/">f</a> <a href="http://beta.onion/">b</a></body></html>  |        |
      | http://falha.onion/   | erro interno                                                                                       | 500    |
      | http://beta.onion/    | <html><body><p>beta</p></body></html>                                                              |        |
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 1
    Então a página "http://beta.onion/" é publicada
    E a página "http://falha.onion/" não é publicada
