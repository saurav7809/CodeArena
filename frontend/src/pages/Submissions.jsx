import { ArrowUpRight, History, Terminal } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { url } from '../api';
import SubmissionResults, { isPending } from '../components/SubmissionResults';
import { BackLink, Badge, dateTime, Empty, LANGUAGE_NAMES, PageTitle, Pagination, Resource, VERDICTS } from '../components/ui';
import { useResource } from '../hooks';

export function SubmissionTable({ items, admin = false }) {
  if (!items.length) return <Empty icon={History} title="No submissions to show">
    {admin ? 'Submissions will appear here after users submit solutions.' : <Link className="btn secondary small" to="/problems">Pick a problem</Link>}
  </Empty>;
  return <div className="panel table-wrap"><table>
    <thead><tr><th>Problem</th>{admin && <th>User</th>}<th>Verdict</th><th>Language</th><th>Runtime</th><th>Submitted</th></tr></thead>
    <tbody>{items.map((item) => <tr key={item.id}>
      <td><Link className="table-title" to={(admin ? '/admin' : '') + '/submissions/' + item.id}>{item.problemTitle}<ArrowUpRight size={14} /></Link></td>
      {admin && <td><Link to={'/users/' + item.username}>{item.username}</Link></td>}
      <td><Badge value={item.verdict} /></td><td>{LANGUAGE_NAMES[item.language]}</td>
      <td>{item.elapsedMs == null ? '—' : item.elapsedMs + ' ms'}</td><td>{dateTime(item.createdAt)}</td>
    </tr>)}</tbody>
  </table></div>;
}

export default function Submissions({ admin = false }) {
  const [query] = useSearchParams();
  const [filter, setFilter] = useState({ problemId: query.get('problemId') || '', language: '', verdict: '', page: 0 });
  const path = (admin ? '/admin' : '') + '/submissions';
  const state = useResource(url(path, { ...filter, userId: admin ? query.get('userId') : null }));
  const catalog = useResource((admin ? '/admin' : '') + '/problems?size=50');
  function change(key, value) { setFilter({ ...filter, [key]: value, page: 0 }); }
  return <div className={admin ? 'admin-content' : 'container page'}>
    {!admin && <BackLink to="/dashboard">My dashboard</BackLink>}
    <PageTitle eyebrow={admin ? 'OPERATIONS' : 'MY ACTIVITY'} title={admin ? 'Manage submissions' : 'Submission history'}
      description="Review judge results and revisit previous solutions." />
    <div className="filters panel">
      <select aria-label="Filter by problem" value={filter.problemId} onChange={(event) => change('problemId', event.target.value)}>
        <option value="">All problems</option>{catalog.data?.items.map((item) => <option key={item.id} value={item.id}>{item.title}</option>)}
      </select>
      <select aria-label="Filter by language" value={filter.language} onChange={(event) => change('language', event.target.value)}>
        <option value="">All languages</option>{Object.entries(LANGUAGE_NAMES).map(([key, label]) => <option value={key} key={key}>{label}</option>)}
      </select>
      <select aria-label="Filter by verdict" value={filter.verdict} onChange={(event) => change('verdict', event.target.value)}>
        <option value="">All verdicts</option>{Object.entries(VERDICTS).map(([key, label]) => <option value={key} key={key}>{label}</option>)}
      </select>
    </div>
    <Resource state={state}>{(data) => <><SubmissionTable items={data.items} admin={admin} />
      <Pagination data={data} onChange={(page) => setFilter({ ...filter, page })} /></>}</Resource>
  </div>;
}

export function SubmissionDetail({ admin = false }) {
  const { id } = useParams();
  const path = (admin ? '/admin' : '') + '/submissions/' + id;
  const state = useResource(path);
  const pending = isPending(state.data?.submission);
  const refresh = state.refresh;
  useEffect(() => {
    if (!pending) return;
    const timer = setTimeout(refresh, 900);
    return () => clearTimeout(timer);
  }, [pending, refresh, state.data]);
  return <div className={admin ? 'admin-content' : 'container page'}>
    <BackLink to={admin ? '/admin/submissions' : '/submissions'}>All submissions</BackLink>
    <Resource state={state}>{(data) => <>
      <PageTitle eyebrow={'SUBMISSION #' + data.submission.id} title={data.submission.problemTitle}
        description={dateTime(data.submission.createdAt) + ' · ' + LANGUAGE_NAMES[data.submission.language]}
        action={<Link className="btn secondary small" to={'/problems/' + data.submission.slug}>Open problem</Link>} />
      <div className="panel padded"><SubmissionResults detail={data} /></div>
      <div className="panel source-panel"><div className="panel-bar"><Terminal size={17} /><strong>Submitted code</strong></div>
        <pre>{data.code}</pre></div>
    </>}</Resource>
  </div>;
}
