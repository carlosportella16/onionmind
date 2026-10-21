/**
 * Abstração sobre provedores de IA — AIOrchestrator (SDD seção 7.2) e a camada de
 * adapters/roteamento por baixo (Fase 3). Não depende de nenhum outro módulo do
 * projeto: só da interface pública deste módulo e de infraestrutura (Redis, HTTP).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {})
package com.onionmind.ai;
