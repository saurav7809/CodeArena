import { BarChart3, CalendarDays, ClipboardList, Code2, Gauge, Plus, Search, Settings2, Shield, Trophy, Users } from 'lucide-react';
import { useState } from 'react';
import { Link, NavLink, Outlet, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import { api, message, url } from '../api';
import { Breakdown, TrendChart } from '../components/Activity';
import { Avatar, BackLink, Badge, CATEGORIES, dateTime, Empty, ErrorNotice, Metric, PageTitle, Pagination,
  Resource, SectionTitle, VERDICTS } from '../components/ui';
import { useApp, useAuth } from '../context';
import { useResource } from '../hooks';
import Submissions, { SubmissionDetail, SubmissionTable } from './Submissions';

const nav = [['', 'Overview', Gauge], ['users', 'Users', Users], ['problems', 'Problems', Code2],
  ['contests', 'Contests', Trophy], ['submissions', 'Submissions', ClipboardList], ['analytics', 'Analytics', BarChart3]];

function Layout() {
  return <div className="container admin-layout">
    <aside className="panel admin-sidebar">
      <div className="panel-bar"><Shield size={18} /><strong>Administration</strong></div>
      <nav aria-label="Admin navigation">{nav.map(([path, label, Icon]) => <NavLink key={label} end={!path}
        to={'/admin' + (path ? '/' + path : '')}><Icon size={17} />{label}</NavLink>)}</nav>
    </aside>
    <Outlet />
  </div>;
}

export default function Admin() {
  return <Routes><Route element={<Layout />}>
    <Route index element={<Overview />} />
    <Route path="analytics" element={<Overview analytics />} />
    <Route path="users" element={<UserList />} />
    <Route path="problems" element={<ProblemList />} />
    <Route path="problems/new" element={<ProblemEdit />} />
    <Route path="problems/:id" element={<ProblemEdit />} />
    <Route path="contests" element={<ContestList />} />
    <Route path="contests/new" element={<ContestEdit />} />
    <Route path="contests/:id" element={<ContestEdit />} />
    <Route path="contests/:id/participants" element={<Participants />} />
    <Route path="submissions" element={<Submissions admin />} />
    <Route path="submissions/:id" element={<SubmissionDetail admin />} />
    <Route path="*" element={<div className="admin-content"><Empty icon={Settings2} title="Admin page not found">
      <Link className="btn secondary" to="/admin">Admin overview</Link></Empty></div>} />
  </Route></Routes>;
}

function Overview({ analytics = false }) {
  const state = useResource(analytics ? '/admin/analytics' : '/admin/dashboard');
  return <div className="admin-content">
    <PageTitle eyebrow="OPERATIONS" title={analytics ? 'Platform analytics' : 'Arena overview'}
      description={analytics ? 'See judging activity and verdict distribution.' : 'Monitor activity and manage the problem and contest libraries.'} />
    <Resource state={state}>{({ totals, accepted, verdicts, activity, recent }) => <>
      <div className="metric-grid admin-metrics">
        <Metric label="Registered users" value={totals.users} icon={Users} /><Metric label="Published problems" value={totals.problems} icon={Code2} />
        <Metric label="Submissions" value={totals.submissions} icon={ClipboardList} /><Metric label="Accepted" value={accepted} icon={Gauge} />
        <Metric label="Live contests" value={totals.activeContests} icon={Trophy} />
      </div>
      {analytics ? <div className="card-grid two page-section">
        <section className="panel padded"><SectionTitle title="Submissions by day" /><TrendChart data={activity} label="Submissions" chartId="admin-activity" /></section>
        <section className="panel padded"><SectionTitle title="Verdicts" />{verdicts.length
          ? <Breakdown items={verdicts.map((item) => ({ ...item, label: VERDICTS[item.label] || item.label }))} />
          : <Empty icon={BarChart3} title="Verdicts will appear after judging" />}</section>
      </div> : <section className="page-section"><SectionTitle title="Recent submissions" to="/admin/submissions" /><SubmissionTable items={recent} admin /></section>}
    </>}</Resource>
  </div>;
}

function UserList() {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const state = useResource(url('/admin/users', { search, page }));
  return <div className="admin-content">
    <PageTitle eyebrow="OPERATIONS" title="Manage users" description="Review account activity and update access." />
    <div className="filters panel"><label className="search-field"><Search size={16} /><input aria-label="Search users" placeholder="Search users"
      value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} /></label></div>
    <Resource state={state}>{(data) => <>
      {data.items.length ? <div className="panel table-wrap"><table>
        <thead><tr><th>Account</th><th>Role and status</th><th>Activity</th></tr></thead>
        <tbody>{data.items.map((account) => <UserRow key={account.id + ':' + account.role + ':' + account.enabled} account={account} refresh={state.refresh} />)}</tbody>
      </table></div> : <Empty icon={Users} title="No users match your search" />}
      <Pagination data={data} onChange={setPage} />
    </>}</Resource>
  </div>;
}

function UserRow({ account, refresh }) {
  const { user } = useAuth();
  const { notify } = useApp();
  const [role, setRole] = useState(account.role);
  const [enabled, setEnabled] = useState(account.enabled);
  const [busy, setBusy] = useState(false);
  const ownAccount = user.id === account.id;
  async function save(event) {
    event.preventDefault();
    setBusy(true);
    try {
      await api.put('/admin/users/' + account.id + '/access', { role, enabled });
      notify('Account access updated.');
      refresh();
    } catch (failure) { notify(message(failure), 'warning'); }
    finally { setBusy(false); }
  }
  return <tr>
    <td><div className="identity"><Avatar username={account.username} size="small" /><div>
      <strong>{account.username}</strong><span>{account.email}</span></div></div></td>
    <td>{ownAccount ? <Badge value={account.role} kind="violet" /> : <form className="row-form" onSubmit={save}>
      <select aria-label={'Role for ' + account.username} value={role} onChange={(event) => setRole(event.target.value)}>
        <option value="USER">User</option><option value="ADMIN">Admin</option>
      </select>
      <label className="checkbox-label"><input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />Enabled</label>
      <button type="submit" className="btn small secondary" disabled={busy || (enabled === account.enabled && role === account.role)}>Save</button>
    </form>}</td>
    <td><Link className="text-link" to={'/admin/submissions?userId=' + account.id}>View attempts</Link></td>
  </tr>;
}

function ProblemList() {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [archive, setArchive] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const state = useResource(url('/admin/problems', { search, page }));
  const { notify } = useApp();

  async function confirmArchive() {
    setBusy(true);
    setError('');
    try {
      await api.delete('/admin/problems/' + archive.id);
      setArchive(null);
      setPage(0);
      state.refresh();
      notify('Problem archived.');
    } catch (failure) { setError(message(failure)); }
    finally { setBusy(false); }
  }

  return <div className="admin-content">
    <PageTitle eyebrow="OPERATIONS" title="Manage problems" description="Publish challenges and configure their tests."
      action={<Link className="btn primary" to="/admin/problems/new"><Plus size={16} />New problem</Link>} />
    <div className="filters panel"><label className="search-field"><Search size={16} /><input aria-label="Search admin problems"
      placeholder="Search problems" value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} /></label></div>
    {archive && <div className="panel padded archive-confirm">
      <div><strong>Archive {archive.title}?</strong><p>Previous submissions will remain in history.</p></div>
      <div className="button-row"><button type="button" className="btn secondary small" onClick={() => setArchive(null)}>Cancel</button>
        <button type="button" className="btn danger small" disabled={busy} onClick={confirmArchive}>Archive problem</button></div>
    </div>}
    {error && <ErrorNotice text={error} />}
    <Resource state={state}>{(data) => <>
      {data.items.length ? <div className="panel table-wrap"><table>
        <thead><tr><th>Title</th><th>Difficulty</th><th>Status</th><th>Actions</th></tr></thead>
        <tbody>{data.items.map((problem) => <tr key={problem.id}>
          <td className="strong">{problem.title}</td><td><Badge value={problem.difficulty} /></td>
          <td><Badge value={problem.published ? 'PUBLISHED' : 'DRAFT'} /></td>
          <td><div className="button-row"><Link className="btn secondary small" to={'/admin/problems/' + problem.id}>Edit</Link>
            <button type="button" className="btn secondary small" onClick={() => { setError(''); setArchive(problem); }}>Archive</button></div></td>
        </tr>)}</tbody>
      </table></div> : <Empty icon={Code2} title="No problems match your search" />}
      <Pagination data={data} onChange={setPage} />
    </>}</Resource>
  </div>;
}

function ProblemEdit() {
  const { id } = useParams();
  const state = useResource(id ? '/admin/problems/' + id : null);
  return <div className="admin-content">
    <BackLink to="/admin/problems">Manage problems</BackLink>
    <PageTitle eyebrow="PROBLEM LIBRARY" title={id ? 'Edit problem' : 'Create a problem'} description="Write the statement, then define sample and hidden tests." />
    {id ? <Resource state={state}>{(data) => <ProblemForm key={id} initial={data} id={id} />}</Resource> : <ProblemForm key="new" />}
  </div>;
}

function blankTest(visible) { return { key: crypto.randomUUID(), input: '', expectedOutput: '', visible }; }

function ProblemForm({ initial, id }) {
  const navigate = useNavigate();
  const { notify } = useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [tests, setTests] = useState(() => initial ? initial.examples.map((item) => ({ ...item, key: crypto.randomUUID() }))
    : [blankTest(true), blankTest(false)]);
  const base = initial?.problem;

  function editTest(key, field, value) {
    setTests((previous) => previous.map((item) => item.key === key ? { ...item, [field]: value } : item));
  }
  async function save(event) {
    event.preventDefault();
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    setError('');
    setBusy(true);
    try {
      const body = { ...fields, timeLimitMs: Number(fields.timeLimitMs), memoryLimitMb: Number(fields.memoryLimitMb),
        published: fields.published === 'on', testCases: tests.map(({ input, expectedOutput, visible }) => ({ input, expectedOutput, visible })) };
      if (id) await api.put('/admin/problems/' + id, body);
      else await api.post('/admin/problems', body);
      notify(id ? 'Problem updated.' : 'Problem created.');
      navigate('/admin/problems');
    } catch (failure) { setError(message(failure)); }
    finally { setBusy(false); }
  }
  return <form onSubmit={save} className="form-stack">
    <section className="panel padded form-stack">
      <div className="form-grid">
        <label>Title<input name="title" required maxLength={100} defaultValue={base?.title} /></label>
        <label>Slug<input name="slug" required pattern="[a-z0-9]+(-[a-z0-9]+)*" maxLength={100} placeholder="unique-problem-name" defaultValue={base?.slug} /></label>
        <label>Difficulty<select name="difficulty" defaultValue={base?.difficulty || 'EASY'}><option value="EASY">Easy</option>
          <option value="MEDIUM">Medium</option><option value="HARD">Hard</option></select></label>
        <label>Category<select name="category" defaultValue={base?.category || 'Arrays'}>{CATEGORIES.map((item) => <option key={item}>{item}</option>)}</select></label>
      </div>
      <label>Description<textarea name="description" required rows={4} maxLength={10000} defaultValue={initial?.description} /></label>
      <div className="form-grid">
        <label>Input format<textarea name="inputFormat" required rows={3} maxLength={2000} defaultValue={initial?.inputFormat} /></label>
        <label>Output format<textarea name="outputFormat" required rows={3} maxLength={2000} defaultValue={initial?.outputFormat} /></label>
        <label>Constraints<textarea name="constraints" required rows={3} maxLength={2000} defaultValue={initial?.constraints} /></label>
        <label>Sample explanation<textarea name="explanation" rows={3} maxLength={2000} defaultValue={initial?.explanation} /></label>
        <label>Time limit (ms)<input type="number" name="timeLimitMs" min={500} max={5000} required defaultValue={initial?.timeLimitMs || 2000} /></label>
        <label>Memory limit (MB)<input type="number" name="memoryLimitMb" min={64} max={512} required defaultValue={initial?.memoryLimitMb || 128} /></label>
      </div>
      <label className="checkbox-label"><input type="checkbox" name="published" defaultChecked={base?.published} />Publish this problem</label>
    </section>
    <section className="panel padded">
      <SectionTitle title="Test cases" description="A published problem needs at least one sample and one hidden test." />
      {tests.map((test, index) => <div className="admin-test" key={test.key}>
        <div className="card-top"><strong>Case {index + 1}</strong><div className="button-row">
          <label className="checkbox-label"><input type="checkbox" checked={test.visible} onChange={(event) => editTest(test.key, 'visible', event.target.checked)} />Show as sample</label>
          <button type="button" className="btn secondary small" onClick={() => setTests(tests.filter((item) => item.key !== test.key))}>Remove</button></div></div>
        <div className="form-grid">
          <label>Input<textarea className="mono" maxLength={8192} rows={3} value={test.input} onChange={(event) => editTest(test.key, 'input', event.target.value)} /></label>
          <label>Expected output<textarea className="mono" maxLength={8192} rows={3} value={test.expectedOutput}
            onChange={(event) => editTest(test.key, 'expectedOutput', event.target.value)} /></label>
        </div>
      </div>)}
      <button type="button" className="btn secondary small" disabled={tests.length >= 12}
        onClick={() => setTests([...tests, blankTest(false)])}><Plus size={15} />Add test case</button>
    </section>
    {error && <ErrorNotice text={error} />}
    <div className="button-row"><button type="submit" className="btn primary" disabled={busy}>{busy ? 'Saving…' : 'Save problem'}</button>
      <Link className="btn secondary" to="/admin/problems">Cancel</Link></div>
  </form>;
}

function ContestList() {
  const [page, setPage] = useState(0);
  const state = useResource(url('/admin/contests', { page }));
  return <div className="admin-content">
    <PageTitle eyebrow="OPERATIONS" title="Manage contests" description="Set contest schedules, problem points, and penalties."
      action={<Link className="btn primary" to="/admin/contests/new"><Plus size={16} />New contest</Link>} />
    <Resource state={state}>{(data) => <>
      {data.items.length ? <div className="panel table-wrap"><table>
        <thead><tr><th>Contest</th><th>Status</th><th>Starts</th><th>Participants</th><th>Actions</th></tr></thead>
        <tbody>{data.items.map((contest) => <tr key={contest.id}>
          <td className="strong">{contest.name}</td><td><Badge value={contest.status} /></td><td>{dateTime(contest.startsAt)}</td>
          <td><Link className="text-link" to={'/admin/contests/' + contest.id + '/participants'}>{contest.participantCount} joined</Link></td>
          <td><Link className="btn secondary small" to={'/admin/contests/' + contest.id}>Edit</Link></td>
        </tr>)}</tbody>
      </table></div> : <Empty icon={CalendarDays} title="Create your first contest" />}
      <Pagination data={data} onChange={setPage} />
    </>}</Resource>
  </div>;
}

function ContestEdit() {
  const { id } = useParams();
  const state = useResource(id ? '/admin/contests/' + id : null);
  return <div className="admin-content">
    <BackLink to="/admin/contests">Manage contests</BackLink>
    <PageTitle eyebrow="CONTEST ARENA" title={id ? 'Edit contest' : 'Create a contest'} description="Choose dates and assign points to each problem." />
    {id ? <Resource state={state}>{(data) => <ContestForm key={id} id={id} initial={data} />}</Resource> : <ContestForm key="new" />}
  </div>;
}

function localDate(value) {
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function ContestForm({ initial, id }) {
  const navigate = useNavigate();
  const { notify } = useApp();
  const [entries, setEntries] = useState(initial?.problems || []);
  const [search, setSearch] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const catalog = useResource(url('/admin/problems', { search, size: 50 }));
  const contest = initial?.contest;
  const locked = Boolean(contest && contest.state !== 'DRAFT' && new Date(contest.startsAt).getTime() <= Date.now());
  const options = catalog.data?.items.filter((item) => !entries.some((entry) => entry.problemId === item.id)) || [];

  function add(idValue) {
    const item = options.find((problem) => problem.id === Number(idValue));
    if (item) setEntries([...entries, { problemId: item.id, title: item.title, points: 100 }]);
  }
  async function save(event) {
    event.preventDefault();
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    setError('');
    setBusy(true);
    try {
      const body = { name: fields.name, description: fields.description, state: fields.state,
        startsAt: locked ? contest.startsAt : new Date(fields.startsAt).toISOString(),
        endsAt: locked ? contest.endsAt : new Date(fields.endsAt).toISOString(),
        penaltyMinutes: locked ? contest.penaltyMinutes : Number(fields.penaltyMinutes),
        problems: entries.map(({ problemId, points }) => ({ problemId, points })) };
      if (id) await api.put('/admin/contests/' + id, body);
      else await api.post('/admin/contests', body);
      notify(id ? 'Contest updated.' : 'Contest created.');
      navigate('/admin/contests');
    } catch (failure) { setError(message(failure)); }
    finally { setBusy(false); }
  }
  return <form className="form-stack" onSubmit={save}>
    <section className="panel padded form-stack">
      <label>Contest name<input name="name" required maxLength={100} defaultValue={contest?.name} /></label>
      <label>Description<textarea name="description" required rows={4} maxLength={3000} defaultValue={contest?.description} /></label>
      <div className="form-grid">
        <label>Starts at<input type="datetime-local" name="startsAt" required disabled={locked} defaultValue={localDate(contest?.startsAt || Date.now() + 3600000)} /></label>
        <label>Ends at<input type="datetime-local" name="endsAt" required disabled={locked} defaultValue={localDate(contest?.endsAt || Date.now() + 7200000)} /></label>
        <label>Penalty minutes per incorrect attempt<input type="number" name="penaltyMinutes" required min={0} max={120} disabled={locked} defaultValue={contest?.penaltyMinutes ?? 20} /></label>
        <label>State<select name="state" defaultValue={contest?.state || 'DRAFT'}>
          {!locked && <option value="DRAFT">Draft</option>}{(!locked || contest.state !== 'CANCELLED') && <option value="PUBLISHED">Published</option>}
          <option value="CANCELLED">Cancelled</option>
        </select></label>
      </div>
    </section>
    <section className="panel padded">
      <SectionTitle title="Contest problems" description={locked ? 'The schedule, problems, and scores are locked after the contest starts.' : 'Add up to twelve problems and set their point values.'} />
      {entries.map((entry) => <div className="contest-entry" key={entry.problemId}><strong>{entry.title}</strong>
        <input type="number" aria-label={'Points for ' + entry.title} min={1} max={1000} required disabled={locked} value={entry.points}
          onChange={(event) => setEntries(entries.map((item) => item.problemId === entry.problemId ? { ...item, points: Number(event.target.value) } : item))} />
        {!locked && <button type="button" className="btn small secondary" onClick={() => setEntries(entries.filter((item) => item.problemId !== entry.problemId))}>Remove</button>}
      </div>)}
      {!locked && <div className="form-grid">
        <label>Search problems<input value={search} onChange={(event) => setSearch(event.target.value)} /></label>
        <label>Add a problem<select value="" onChange={(event) => add(event.target.value)} disabled={entries.length >= 12}>
          <option value="">Choose a problem</option>{options.map((item) => <option key={item.id} value={item.id}>{item.title}</option>)}
        </select></label>
      </div>}
      {catalog.error && <ErrorNotice text={catalog.error} retry={catalog.refresh} />}
    </section>
    {error && <ErrorNotice text={error} />}
    <div className="button-row"><button type="submit" className="btn primary" disabled={busy}>{busy ? 'Saving…' : 'Save contest'}</button>
      <Link className="btn secondary" to="/admin/contests">Cancel</Link></div>
  </form>;
}

function Participants() {
  const { id } = useParams();
  const [page, setPage] = useState(0);
  const state = useResource(url('/admin/contests/' + id + '/participants', { page }));
  return <div className="admin-content">
    <BackLink to="/admin/contests">Manage contests</BackLink><PageTitle eyebrow="CONTEST ARENA" title="Participants" />
    <Resource state={state}>{(data) => <>
      {data.items.length ? <div className="panel table-wrap"><table><thead><tr><th>Username</th><th>Registered</th></tr></thead>
        <tbody>{data.items.map((item) => <tr key={item.username}><td><Link className="table-title" to={'/users/' + item.username}>{item.username}</Link></td>
          <td>{dateTime(item.registeredAt)}</td></tr>)}</tbody></table></div> : <Empty icon={Users} title="No participants yet" />}
      <Pagination data={data} onChange={setPage} />
    </>}</Resource>
  </div>;
}
