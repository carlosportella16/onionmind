import { useState } from 'react';
import { search, searchSemantic, type SearchResult } from './api/search';
import { SearchBar } from './components/SearchBar';
import { ResultList } from './components/ResultList';

function App() {
  const [results, setResults] = useState<SearchResult[]>([]);
  const [total, setTotal] = useState(0);

  const handleSearch = async (query: string, semantic: boolean) => {
    const data = semantic ? await searchSemantic(query) : await search(query);
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
