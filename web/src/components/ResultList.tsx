import DOMPurify from 'dompurify';
import type { SearchResult } from '../api/search';

interface Props {
  results: SearchResult[];
  total: number;
}

// O backend só emite <b> pra destacar o termo buscado (ts_headline), mas o
// texto ao redor vem de HTML de terceiros — sanitiza de novo no cliente
// como defesa em profundidade antes de injetar via dangerouslySetInnerHTML.
function sanitizeSnippet(snippet: string): string {
  return DOMPurify.sanitize(snippet, { ALLOWED_TAGS: ['b'], ALLOWED_ATTR: [] });
}

export function ResultList({ results, total }: Readonly<Props>) {
  return (
    <div>
      <p>{total} resultados</p>
      {results.map((r) => (
        <div key={r.id}>
          <a href={r.url}>{r.url}</a>
          <span> v{r.version}</span>
          <p dangerouslySetInnerHTML={{ __html: sanitizeSnippet(r.snippet) }} />
        </div>
      ))}
    </div>
  );
}
