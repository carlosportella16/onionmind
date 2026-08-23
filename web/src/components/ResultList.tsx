import DOMPurify from 'dompurify';
import type { SearchResult } from '../api/search';

interface Props {
  results: SearchResult[];
  total: number;
  hasSearched: boolean;
}

// O backend só emite <b> pra destacar o termo buscado (ts_headline), mas o
// texto ao redor vem de HTML de terceiros — sanitiza de novo no cliente
// como defesa em profundidade antes de injetar via dangerouslySetInnerHTML.
function sanitizeSnippet(snippet: string): string {
  return DOMPurify.sanitize(snippet, { ALLOWED_TAGS: ['b'], ALLOWED_ATTR: [] });
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('pt-BR', { day: '2-digit', month: 'short', year: 'numeric' });
}

export function ResultList({ results, total, hasSearched }: Readonly<Props>) {
  if (!hasSearched) {
    return null;
  }

  if (results.length === 0) {
    return (
      <div className="results">
        <p className="empty-state">$ nenhum resultado encontrado</p>
      </div>
    );
  }

  return (
    <div className="results">
      <p className="results-count">
        <span className="results-count-number">{total}</span> resultado{total === 1 ? '' : 's'}
      </p>

      <div className="results-list">
        {results.map((r, i) => (
          <div className="result-row" key={r.id}>
            <div className="result-line">
              <span className="result-branch">{i === results.length - 1 ? '└─' : '├─'}</span>
              <a href={r.url} target="_blank" rel="noreferrer">
                {r.url}
              </a>
              <span className="result-version">v{r.version}</span>
            </div>
            <p
              className="result-snippet"
              dangerouslySetInnerHTML={{ __html: sanitizeSnippet(r.snippet) }}
            />
            <div className="result-meta">
              score {r.rank.toFixed(2)} · indexado {formatDate(r.firstSeenAt)} · fonte {r.sourceType}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
