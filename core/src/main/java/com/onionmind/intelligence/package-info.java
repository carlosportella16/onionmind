/**
 * Reage a {@code PageIndexedEvent} (ADR-009) por dois motivos independentes: resumir o que
 * mudou entre versões (master-sdd sec. 5/8.2) e avaliar regras de alerta simples (master-sdd
 * sec. 6.6) — nenhum dos dois conhece {@code graph}, e {@code ingestion} não conhece nenhum
 * dos dois (Fase 4).
 */
@org.springframework.modulith.ApplicationModule
package com.onionmind.intelligence;
