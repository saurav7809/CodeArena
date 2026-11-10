import { ArrowUpRight, Check, Circle, Code2, Search } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { url } from '../api';
import { useAuth } from '../context';
import { useResource } from '../hooks';
import { Badge, CATEGORIES, Empty, PageTitle, Pagination, Resource } from '../components/ui';

export default function Problems() {
  const { user } = useAuth();
  const [filters, setFilters] = useState({ search: '', difficulty: '', category: '', solved: 'ALL', sort: 'number', page: 0 });
  const state = useResource(url('/problems', filters));
  function setFilter(key, value) { setFilters({ ...filters, [key]: value, page: 0 }); }

  return <div className="container page">
    <PageTitle eyebrow="PRACTICE LIBRARY" title="Problems" description="Find a challenge, build a solution, and test it in the arena." />
    <div className="filters panel">
      <label className="search-field"><Search size={17} /><input aria-label="Search problems" placeholder="Search problems" maxLength={100}
        value={filters.search} onChange={(event) => setFilter('search', event.target.value)} /></label>
      <select aria-label="Difficulty" value={filters.difficulty} onChange={(event) => setFilter('difficulty', event.target.value)}>
        <option value="">All difficulties</option><option value="EASY">Easy</option><option value="MEDIUM">Medium</option><option value="HARD">Hard</option>
      </select>
      <select aria-label="Category" value={filters.category} onChange={(event) => setFilter('category', event.target.value)}>
        <option value="">All topics</option>{CATEGORIES.map((category) => <option key={category}>{category}</option>)}
      </select>
      {user && <select aria-label="Solved status" value={filters.solved} onChange={(event) => setFilter('solved', event.target.value)}>
        <option value="ALL">Any status</option><option value="SOLVED">Solved</option><option value="UNSOLVED">Unsolved</option>
      </select>}
      <select aria-label="Sort problems" value={filters.sort} onChange={(event) => setFilter('sort', event.target.value)}>
        <option value="number">Problem number</option><option value="difficulty">Difficulty</option><option value="newest">Newest first</option>
      </select>
    </div>
    <Resource state={state}>{(data) => <>
      <p className="result-count">{data.total} {data.total === 1 ? 'problem' : 'problems'}</p>
      {data.items.length ? <div className="panel table-wrap"><table>
        <thead><tr><th>Status</th><th>Title</th><th>Difficulty</th><th>Topic</th><th>Acceptance</th></tr></thead>
        <tbody>{data.items.map((problem) => <tr key={problem.id}>
          <td>{problem.solved ? <Check size={18} className="success-text" aria-label="Solved" /> : <Circle size={14} className="muted" aria-label="Unsolved" />}</td>
          <td><Link className="table-title" to={'/problems/' + problem.slug}>{problem.title}<ArrowUpRight size={14} /></Link></td>
          <td><Badge value={problem.difficulty} /></td><td>{problem.category}</td>
          <td>{problem.submissions ? Math.round(problem.accepted / problem.submissions * 100) + '%' : '—'}</td>
        </tr>)}</tbody>
      </table></div> : <Empty icon={Code2} title="No problems match your filters">
        <button type="button" className="btn secondary small" onClick={() => setFilters({ search: '', difficulty: '', category: '', solved: 'ALL', sort: 'number', page: 0 })}>Clear filters</button>
      </Empty>}
      <Pagination data={data} onChange={(page) => setFilters({ ...filters, page })} />
    </>}</Resource>
  </div>;
}
