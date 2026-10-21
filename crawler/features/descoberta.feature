# language: pt
Funcionalidade: Descoberta de links
  Links .onion são seguidos até a profundidade máxima; links clearnet são ignorados.

  Contexto:
    Dado os sites onion abaixo:
      | url                   | html                                                                                                   |
      | http://semente.onion/ | <html><body><a href="http://nivel1.onion/">n1</a> <a href="http://exemplo.com/">clearnet</a></body></html> |
      | http://nivel1.onion/  | <html><body><p>nível 1</p><a href="http://nivel2.onion/">n2</a></body></html>                          |
      | http://nivel2.onion/  | <html><body><p>nível 2</p></body></html>                                                               |

  Cenário: Link onion dentro da profundidade é seguido
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 1
    Então a página "http://semente.onion/" é publicada
    E a página "http://nivel1.onion/" é publicada

  Cenário: Profundidade máxima é respeitada
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 1
    Então a página "http://nivel1.onion/" é publicada
    E a página "http://nivel2.onion/" não é publicada

  Cenário: Link clearnet é ignorado
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 1
    Então a página "http://nivel1.onion/" é publicada
    E o proxy não recebeu requisição para "exemplo.com"
    E a página "http://exemplo.com/" não é publicada
