/**
 * Busca full-text sobre páginas indexadas, sem dependência de IA
 * (SDD Fase 1, seção 6.8). Lê/escreve `pages` via SQL direto, sem
 * depender do módulo `ingestion` (design.md Decision 5).
 *
 * Fase 2 adiciona o índice vetorial (Qdrant) para busca semântica,
 * combinada com o full-text acima (SDD Fase 2, seção 3).
 */
@org.springframework.modulith.ApplicationModule
package com.onionmind.search;
