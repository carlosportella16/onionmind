import { useState } from 'react';
import { ask, MAX_QUESTION_CHARS, type ChatResponse } from '../api/chat';

// Page text comes from third-party .onion sites: every snippet here is rendered as plain
// text (React escapes it), never as HTML. The chat UI is English (phase5-rag-chat D7).
export function Chat() {
  const [question, setQuestion] = useState('');
  const [response, setResponse] = useState<ChatResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async () => {
    if (!question.trim() || loading) return;
    setLoading(true);
    setError(null);
    try {
      setResponse(await ask(question));
    } catch (e) {
      setResponse(null);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="chat">
      <div className="search-input">
        <span className="search-prompt">?</span>
        <input
          value={question}
          maxLength={MAX_QUESTION_CHARS}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          placeholder="ask about the indexed pages..."
          aria-label="Question"
        />
        <button type="button" onClick={submit} disabled={loading}>
          {loading ? 'asking…' : 'ask'}
        </button>
      </div>

      {error && <p className="empty-state">$ {error}</p>}

      {response && (
        <div className="results">
          {response.degraded ? (
            <p className="chat-degraded" role="status">
              The answer was not ready in time. Showing the search results instead.
            </p>
          ) : (
            <p className="chat-answer">{response.answer}</p>
          )}

          {response.sources.length > 0 && (
            <>
              <p className="results-count">sources</p>
              <div className="results-list">
                {response.sources.map((s) => (
                  <div className="result-row" key={s.id}>
                    <div className="result-line">
                      <span className="result-version">{s.id}</span>
                      <a href={s.url} target="_blank" rel="noreferrer">
                        {s.url}
                      </a>
                    </div>
                    <p className="result-snippet">{s.snippet}</p>
                  </div>
                ))}
              </div>
            </>
          )}

          <p className="results-count">
            <span className="results-count-number">{response.results.length}</span> search result
            {response.results.length === 1 ? '' : 's'}
          </p>
          <div className="results-list">
            {response.results.map((r) => (
              <div className="result-row" key={r.id}>
                <div className="result-line">
                  <a href={r.url} target="_blank" rel="noreferrer">
                    {r.url}
                  </a>
                </div>
                <p className="result-snippet">{r.snippet}</p>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
