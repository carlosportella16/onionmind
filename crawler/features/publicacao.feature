# language: pt
Funcionalidade: Publicação no raw-pages
  O crawler coleta páginas .onion pelo proxy SOCKS5 (o Tor) e publica cada uma
  no tópico com o contrato JSON que o core consome.

  Cenário: Página semente publicada no contrato do core
    Dado os sites onion abaixo:
      | url                  | html                                                  |
      | http://semente.onion/ | <html><body><p>Mercado de teste da semente</p></body></html> |
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 0
    Então a página "http://semente.onion/" é publicada
    E a mensagem de "http://semente.onion/" tem a URL como chave, source_type "tor", html contendo "<p>Mercado de teste da semente</p>" e fetched_at em RFC 3339

  Cenário: Nome .onion é resolvido pelo proxy, não localmente
    Dado os sites onion abaixo:
      | url                  | html                              |
      | http://semente.onion/ | <html><body><p>oi</p></body></html> |
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 0
    Então a página "http://semente.onion/" é publicada
    E o proxy recebeu o hostname "semente.onion"
