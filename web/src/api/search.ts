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
  const res = await fetch(`/api/search?q=${encodeURIComponent(query)}`);
  if (!res.ok) {
    throw new Error(`search failed: ${res.status}`);
  }
  return res.json();
}
