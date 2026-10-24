# language: pt
Funcionalidade: Listagem em texto e denylist de hosts
  Endereços .onion v3 em texto puro, sem âncora (como em ahmia.fi/onions/), são seguidos.
  Hosts da denylist do crawler nunca são buscados, nem como semente. Hosts sintéticos.

  Contexto:
    Dado os sites onion abaixo:
      | url                                                                          | html                                                                                                                                                                                     |
      | http://listagem.onion/                                                       | <html><body>  http://3hcyaeil5gozk5r3iad6vlg6ouzkudgykbg7cad5oss6xm4ct76bsbbk.onion/<br>  http://xzcixfh2bpvdoaa6qlo5zam4wb5rhu3j5q7ekm57yth4i6wngz2x3erb.onion/<br><a href="HTTPS://XZCIXFH2BPVDOAA6QLO5ZAM4WB5RHU3J5Q7EKM57YTH4I6WNGZ2X3ERB.onion/forum">forum</a></body></html> |
      | http://3hcyaeil5gozk5r3iad6vlg6ouzkudgykbg7cad5oss6xm4ct76bsbbk.onion/ | <html><body><p>host listado</p></body></html>                                                                                                                                            |
      | http://xzcixfh2bpvdoaa6qlo5zam4wb5rhu3j5q7ekm57yth4i6wngz2x3erb.onion/ | <html><body><p>host banido</p></body></html>                                                                                                                                             |
    E o host "xzcixfh2bpvdoaa6qlo5zam4wb5rhu3j5q7ekm57yth4i6wngz2x3erb.onion" está na denylist do crawler

  Cenário: Endereço em texto leva a host novo e o host banido não é buscado
    Quando o crawler roda com a semente "http://listagem.onion/" e profundidade máxima 1
    Então a página "http://3hcyaeil5gozk5r3iad6vlg6ouzkudgykbg7cad5oss6xm4ct76bsbbk.onion/" é publicada
    E o proxy não recebeu requisição para "xzcixfh2bpvdoaa6qlo5zam4wb5rhu3j5q7ekm57yth4i6wngz2x3erb.onion"
    E a página "http://xzcixfh2bpvdoaa6qlo5zam4wb5rhu3j5q7ekm57yth4i6wngz2x3erb.onion/" não é publicada

  Cenário: Semente banida é ignorada
    Quando o crawler roda com a semente "http://xzcixfh2bpvdoaa6qlo5zam4wb5rhu3j5q7ekm57yth4i6wngz2x3erb.onion/" e profundidade máxima 1
    Então o proxy não recebeu requisição para "xzcixfh2bpvdoaa6qlo5zam4wb5rhu3j5q7ekm57yth4i6wngz2x3erb.onion"
    E a página "http://xzcixfh2bpvdoaa6qlo5zam4wb5rhu3j5q7ekm57yth4i6wngz2x3erb.onion/" não é publicada
