# language: pt
Funcionalidade: Encerramento limpo
  Cancelar o contexto drena os workers e encerra sem erro.

  Cenário: Cancelamento durante a coleta
    Dado os sites onion abaixo:
      | url                   | html                              |
      | http://semente.onion/ | <html><body><p>oi</p></body></html> |
    Quando o crawler roda com a semente "http://semente.onion/" e profundidade máxima 0
    E a página "http://semente.onion/" é publicada
    E o crawler é cancelado
    Então a execução termina sem erro em até 10 segundos
