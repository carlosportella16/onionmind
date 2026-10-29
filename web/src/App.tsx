import { useState } from 'react';
import { search, searchSemantic, type SearchResult } from './api/search';
import { SearchBar } from './components/SearchBar';
import { ResultList } from './components/ResultList';
import { Chat } from './components/Chat';
import { Agents } from './components/Agents';
import './App.css';

type Tab = 'search' | 'chat' | 'agents';

function App() {
  const [tab, setTab] = useState<Tab>('search');
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
        <nav className="app-tabs">
          {(['search', 'chat', 'agents'] as const).map((t) => (
            <button
              key={t}
              type="button"
              className={tab === t ? 'active' : undefined}
              aria-pressed={tab === t}
              onClick={() => setTab(t)}
            >
              {t}
            </button>
          ))}
        </nav>
      </header>

      <main>
        {tab === 'search' && (
          <>
            <SearchBar onSearch={handleSearch} />
            <ResultList results={results} total={total} hasSearched={hasSearched} />
          </>
        )}
        {tab === 'chat' && <Chat />}
        {tab === 'agents' && <Agents />}
      </main>
    </div>
  );
}

export default App;
