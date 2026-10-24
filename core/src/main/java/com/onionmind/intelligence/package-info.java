/**
 * "What changed" (Fase 4): diffs each new enriched version of a page against the last
 * enriched one, summarizes real changes on a schedule and serves them without calling an
 * LLM live. Reads/writes {@code pages}, {@code page_versions} and {@code page_changes} with
 * SQL, like the search module does with {@code pages} (design D1).
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"ingestion", "ai", "content"})
package com.onionmind.intelligence;
