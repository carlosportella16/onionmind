import { useState } from 'react';

interface Props {
  onSearch: (query: string, semantic: boolean) => void;
}

export function SearchBar({ onSearch }: Readonly<Props>) {
  const [query, setQuery] = useState('');
  const [semantic, setSemantic] = useState(false);

  const submit = () => {
    if (!query.trim()) return;
    onSearch(query, semantic);
  };

  return (
    <div className="search-bar">
      <div className="search-input">
        <span className="search-prompt">&gt;</span>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          placeholder="buscar na rede tor..."
          aria-label="Termo de busca"
        />
        <span className="search-cursor" aria-hidden="true" />
        <button type="button" onClick={submit}>
          buscar
        </button>
      </div>

      <label className="search-toggle">
        <input
          type="checkbox"
          checked={semantic}
          onChange={(e) => setSemantic(e.target.checked)}
        />
        <span className="toggle-track">
          <span className="toggle-thumb" />
        </span>
        <span>
          busca_semantica{' '}
          <span className="toggle-state">{`// ${semantic ? 'on' : 'off'}`}</span> — busca por
          conceito, não só por palavra literal
        </span>
      </label>
    </div>
  );
}
