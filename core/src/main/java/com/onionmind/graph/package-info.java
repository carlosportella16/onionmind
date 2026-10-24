/**
 * Knowledge graph (Fase 4): which pages and onion services mention which entities, kept in
 * Neo4j as a projection of {@code pages.entities} — Postgres stays the source of truth.
 * Fed only by ingestion's events (ADR-009); uses content for the entity types. Off unless
 * {@code graph.enabled=true}.
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"ingestion", "content"})
package com.onionmind.graph;
