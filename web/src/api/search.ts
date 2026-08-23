export interface SearchResult {
  id: number;
  url: string;
  sourceType: string;
  snippet: string;
  rank: number;
  version: number;
  firstSeenAt: string;
  lastSeenAt: string;
}

export interface SearchResponse {
  results: SearchResult[];
  total: number;
  page: number;
  size: number;
}

async function runSearch(path: string, query: string): Promise<SearchResponse> {
  const trimmed = query.trim().slice(0, 200);
  if (!trimmed) {
    throw new Error('search query must not be empty');
  }

  const params = new URLSearchParams({ q: trimmed });
  const res = await fetch(`${path}?${params.toString()}`);
  if (!res.ok) {
    throw new Error(`search failed: ${res.status}`);
  }
  return res.json();
}

// Full-text (Fase 1); combina com busca vetorial automaticamente quando embeddings
// estão habilitados no backend (Fase 2, fusão simples).
export function search(query: string): Promise<SearchResponse> {
  return runSearch('/api/search', query);
}

// Busca por conceito, ignorando correspondência lexical (Fase 2). Retorna 503 se o
// backend estiver rodando com embedding.enabled=false.
export function searchSemantic(query: string): Promise<SearchResponse> {
  return runSearch('/api/search/semantic', query);
}
