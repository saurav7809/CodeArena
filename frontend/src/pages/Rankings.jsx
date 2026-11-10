import { Trophy } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { url } from '../api';
import { useResource } from '../hooks';
import { Avatar, Empty, number, PageTitle, Pagination, Resource } from '../components/ui';

export function RankingTable({ data, onPage, contest = false }) {
  if (!data.items.length) return <Empty icon={Trophy} title={contest ? 'No registered competitors yet' : 'The leaderboard is ready for its first result'}>
    {contest ? 'Register for the contest to appear here.' : <Link className="btn secondary small" to="/problems">Try a problem</Link>}
  </Empty>;
  return <>
    <div className="panel table-wrap"><table>
      <thead><tr><th>Rank</th><th>Competitor</th><th>Solved</th><th>Points</th>{contest && <th>Penalty</th>}<th>Acceptance</th></tr></thead>
      <tbody>{data.items.map((entry) => <tr key={entry.username}>
        <td><span className={'rank-chip rank-' + entry.rank}>#{entry.rank}</span></td>
        <td><Link to={'/users/' + entry.username} className="table-title"><Avatar username={entry.username} size="small" />{entry.username}</Link></td>
        <td>{number(entry.solved)}</td><td className="mono strong">{number(entry.points)}</td>
        {contest && <td>{number(entry.penalty)} min</td>}<td>{entry.acceptance}%</td>
      </tr>)}</tbody>
    </table></div>
    <Pagination data={data} onChange={onPage} />
  </>;
}

export default function Rankings() {
  const [period, setPeriod] = useState('global');
  const [sort, setSort] = useState('points');
  const [page, setPage] = useState(0);
  const state = useResource(url('/leaderboard/' + period, { sort, page }));
  return <div className="container page">
    <PageTitle eyebrow="THE STANDINGS" title="Leaderboard" description="Rankings based on judged submissions and distinct problems solved." />
    <div className="filter-row">
      <div className="tabs" aria-label="Ranking period">{[['global', 'All time'], ['weekly', 'This week'], ['monthly', 'This month']].map(([key, label]) =>
        <button type="button" key={key} aria-pressed={period === key} className={period === key ? 'active' : ''}
          onClick={() => { setPeriod(key); setPage(0); }}>{label}</button>)}</div>
      <select aria-label="Sort leaderboard" value={sort} onChange={(event) => { setSort(event.target.value); setPage(0); }}>
        <option value="points">By points</option><option value="solved">By solved</option><option value="acceptance">By acceptance</option>
      </select>
    </div>
    <Resource state={state}>{(data) => <RankingTable data={data} onPage={setPage} />}</Resource>
  </div>;
}
