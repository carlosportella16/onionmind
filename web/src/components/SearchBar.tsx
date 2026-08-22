import { useState } from 'react';

interface Props {
  onSearch: (query: string) => void;
}

export function SearchBar({ onSearch }: Props) {
  const [query, setQuery] = useState('');

  const submit = () => {
    if (!query.trim()) return;
    onSearch(query);
  };

  return (
    <div>
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => e.key === 'Enter' && submit()}
        placeholder="Buscar na rede Tor..."
      />
      <button onClick={submit}>Buscar</button>
    </div>
  );
}
