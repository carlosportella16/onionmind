/**
 * Chat sobre a base (Fase 5, phase5-rag-chat): reescreve a pergunta, recupera trechos pelo
 * {@code PassageRetriever} do módulo search e gera uma resposta citada em inglês pelo
 * AIOrchestrator. Não acessa Postgres nem Qdrant diretamente (design D1).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"search", "ai"})
package com.onionmind.chat;
