import { useState } from 'react';
import { search, type SearchResult } from './api/search';
import { SearchBar } from './components/SearchBar';
import { ResultList } from './components/ResultList';

function App() {
  const [results, setResults] = useState<SearchResult[]>([]);
  const [total, setTotal] = useState(0);

  const handleSearch = async (query: string) => {
    const data = await search(query);
    setResults(data.results);
    setTotal(data.total);
  };

  return (
    <div>
      <h1>OnionMind</h1>
      <SearchBar onSearch={handleSearch} />
      <ResultList results={results} total={total} />
    </div>
  );
}

export default App;
