/**
 * Busca full-text sobre páginas indexadas, sem dependência de IA
 * (SDD Fase 1, seção 6.8). Lê/escreve `pages` via SQL direto, sem
 * depender do módulo `ingestion` (design.md Decision 5).
 */
@org.springframework.modulith.ApplicationModule
package com.onionmind.search;
