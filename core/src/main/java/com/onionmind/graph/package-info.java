/**
 * Extrai entidades de páginas indexadas e persiste num grafo consultável (Neo4j AuraDB Free,
 * master-sdd sec. 10), reagindo a {@code PageIndexedEvent} sem que {@code ingestion}/{@code
 * content} conheçam este módulo (ADR-009, master-sdd sec. 6.6 — Fase 4).
 */
@org.springframework.modulith.ApplicationModule
package com.onionmind.graph;
