import type { SearchResult } from './search';

export interface ChatSource {
  id: string;
  url: string;
  snippet: string;
}

// answer is English and empty when degraded; sources are only the passages the answer cites.
export interface ChatResponse {
  answer: string;
  sources: ChatSource[];
  results: SearchResult[];
  degraded: boolean;
}

// Matches the core's chat.max-question-chars default; longer questions are a 400.
export const MAX_QUESTION_CHARS = 1000;

// RAG chat (Fase 5): one independent question per call, no history.
export async function ask(question: string): Promise<ChatResponse> {
  const trimmed = question.trim();
  if (!trimmed) {
    throw new Error('question must not be empty');
  }

  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question: trimmed.slice(0, MAX_QUESTION_CHARS) }),
  });
  if (!res.ok) {
    throw new Error(`chat failed: ${res.status}`);
  }
  return res.json();
}
