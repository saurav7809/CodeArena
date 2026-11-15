import { ArrowUpRight, Check, Circle, Code2, Search, X } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { url } from '../api';
import { useAuth } from '../context';
import { useResource } from '../hooks';
import { Badge, CATEGORIES, Empty, number, PageTitle, Pagination, Resource } from '../components/ui';

const defaultFilters = { search: '', difficulty: '', category: '', solved: 'ALL', sort: 'number', page: 0 };

export default function Problems() {
  const { user } = useAuth();
  const [filters, setFilters] = useState(defaultFilters);
  const state = useResource(url('/problems', filters));
  function setFilter(key, value) { setFilters((current) => ({ ...current, [key]: value, page: 0 })); }

  return <div className="container page catalog">
    <PageTitle title="Problem library" description="Practice data structures and algorithms by topic or difficulty." />
    <section className="panel catalog-sheet" aria-label="Practice problems">
      <div className="filters catalog-filters">
        <div className="search-field">
          <Search size={17} aria-hidden="true" />
          <input type="text" aria-label="Search problems" placeholder="Search problems" maxLength={100}
            value={filters.search} onChange={(event) => setFilter('search', event.target.value)} />
          {filters.search && <button type="button" className="icon-button clear-search" aria-label="Clear search" onClick={() => setFilter('search', '')}><X size={16} /></button>}
        </div>
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
          <option value="number">Default order</option><option value="difficulty">Difficulty</option><option value="newest">Newest first</option>
        </select>
      </div>
      <Resource state={state}>{(data) => <>
        <p className="catalog-count" role="status">{number(data.total)} {data.total === 1 ? 'problem' : 'problems'}</p>
        {data.items.length ? <div className="table-wrap"><table className="problem-table">
          <thead><tr>
            <th scope="col" className="problem-status">Status</th>
            <th scope="col">Title</th>
            <th scope="col">Difficulty</th>
            <th scope="col" className="problem-topic">Topic</th>
            <th scope="col" className="problem-acceptance">Acceptance</th>
          </tr></thead>
          <tbody>{data.items.map((problem) => {
            const acceptance = problem.submissions ? Math.round(problem.accepted / problem.submissions * 100) + '%' : null;
            return <tr key={problem.id}>
              <td className="problem-status">{problem.solved ? <Check size={17} className="success-text" role="img" aria-label="Solved" />
                : <Circle size={13} className="muted" role="img" aria-label="Unsolved" />}</td>
              <td className="problem-name">
                <Link className="table-title" to={'/problems/' + problem.slug}>{problem.title}<ArrowUpRight size={14} aria-hidden="true" /></Link>
                <span className="problem-mobile-meta">{problem.category}{acceptance && ' · ' + acceptance + ' accepted'}</span>
              </td>
              <td><Badge value={problem.difficulty} /></td>
              <td className="problem-topic">{problem.category}</td>
              <td className="problem-acceptance">{acceptance ?? '—'}</td>
            </tr>;
          })}</tbody>
        </table></div> : <Empty icon={Code2} title="No problems match your filters">
          <button type="button" className="btn secondary small" onClick={() => setFilters(defaultFilters)}>Clear filters</button>
        </Empty>}
        <Pagination data={data} onChange={(page) => setFilters({ ...filters, page })} />
      </>}</Resource>
    </section>
  </div>;
}
