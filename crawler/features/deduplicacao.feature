# language: pt
Funcionalidade: Deduplicação no Redis
  URLs são deduplicadas pela forma normalizada; sem Redis o crawler segue coletando.

  Cenário: Variante normalizada não é recoletada
    Dado os sites onion abaixo:
      | url                   | html                                                                                                  |
      | http://semente.onion/ | <html><body><a href="http://semente.onion/#topo">topo</a> <a href="http://beta.onion/">beta</a></body></html> |
      | http://beta.onion/    | <html><body><p>beta</p></body></html>                                                                 |
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 1
    Então a página "http://beta.onion/" é publicada
    E o site recebeu 1 requisição para "http://semente.onion/"

  Cenário: Visita registrada no Redis com expiração
    Dado os sites onion abaixo:
      | url                   | html                              |
      | http://semente.onion/ | <html><body><p>oi</p></body></html> |
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 0
    Então a página "http://semente.onion/" é publicada
    E o Redis tem a chave de visita de "http://semente.onion/" com TTL positivo

  Cenário: Redis indisponível não para o crawler
    Dado os sites onion abaixo:
      | url                   | html                              |
      | http://semente.onion/ | <html><body><p>oi</p></body></html> |
    E o Redis está indisponível
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 0
    Então a página "http://semente.onion/" é publicada
