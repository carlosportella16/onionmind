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
    <div>
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()}
        placeholder="Buscar na rede Tor..."
      />
      <label>
        <input
          type="checkbox"
          checked={semantic}
          onChange={(e) => setSemantic(e.target.checked)}
        />
        Busca semântica
      </label>
      <button type="button" onClick={submit}>Buscar</button>
    </div>
  );
}
