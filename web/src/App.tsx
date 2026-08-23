import { useState } from 'react';
import { search, searchSemantic, type SearchResult } from './api/search';
import { SearchBar } from './components/SearchBar';
import { ResultList } from './components/ResultList';
import './App.css';

function App() {
  const [results, setResults] = useState<SearchResult[]>([]);
  const [total, setTotal] = useState(0);
  const [hasSearched, setHasSearched] = useState(false);

  const handleSearch = async (query: string, semantic: boolean) => {
    const data = semantic ? await searchSemantic(query) : await search(query);
    setResults(data.results);
    setTotal(data.total);
    setHasSearched(true);
  };

  return (
    <div className="app">
      <header className="app-header">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="12" r="9.5" stroke="var(--accent)" strokeWidth="1.4" />
          <circle cx="12" cy="12" r="6" stroke="var(--accent)" strokeWidth="1.4" opacity="0.65" />
          <circle cx="12" cy="12" r="2.2" fill="var(--accent)" />
        </svg>
        <span className="app-name">onionmind</span>
        <span className="app-tagline">search the tor network</span>
      </header>

      <main>
        <SearchBar onSearch={handleSearch} />
        <ResultList results={results} total={total} hasSearched={hasSearched} />
      </main>
    </div>
  );
}

export default App;
