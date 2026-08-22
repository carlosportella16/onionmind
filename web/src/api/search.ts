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

export async function search(query: string): Promise<SearchResponse> {
  const trimmed = query.trim().slice(0, 200);
  if (!trimmed) {
    throw new Error('search query must not be empty');
  }

  const params = new URLSearchParams({ q: trimmed });
  const res = await fetch(`/api/search?${params.toString()}`);
  if (!res.ok) {
    throw new Error(`search failed: ${res.status}`);
  }
  return res.json();
}
