import type { SearchResult } from '../api/search';

interface Props {
  results: SearchResult[];
  total: number;
}

export function ResultList({ results, total }: Props) {
  return (
    <div>
      <p>{total} resultados</p>
      {results.map((r) => (
        <div key={r.id}>
          <a href={r.url}>{r.url}</a>
          <span> v{r.version}</span>
          <p dangerouslySetInnerHTML={{ __html: r.snippet }} />
        </div>
      ))}
    </div>
  );
}
