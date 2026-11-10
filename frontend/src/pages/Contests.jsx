import { ArrowRight, CalendarDays, Clock3, Trophy, Users } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, message, url } from '../api';
import { useApp, useAuth } from '../context';
import { useResource } from '../hooks';
import { BackLink, Badge, dateTime, Empty, ErrorNotice, PageTitle, Pagination, Resource, SectionTitle } from '../components/ui';
import { RankingTable } from './Rankings';

export default function Contests() {
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState(0);
  const state = useResource(url('/contests', { status, page }));
  return <div className="container page">
    <PageTitle eyebrow="COMPETE" title="Contests" description="Enter a round, solve against the clock, and climb the standings." />
    <div className="tabs filters-bottom" aria-label="Contest status">
      {[['ALL', 'All contests'], ['ONGOING', 'Live'], ['UPCOMING', 'Upcoming'], ['PAST', 'Past']].map(([key, label]) =>
        <button type="button" key={key} aria-pressed={status === key} className={status === key ? 'active' : ''}
          onClick={() => { setStatus(key); setPage(0); }}>{label}</button>)}
    </div>
    <Resource state={state}>{(data) => <>
      {data.items.length ? <div className="card-grid two">{data.items.map((contest) =>
        <Link key={contest.id} className="panel contest-card linked-card" to={'/contests/' + contest.id}>
          <div className="card-top"><div className="feature-icon"><Trophy size={20} /></div><Badge value={contest.status} /></div>
          <h2>{contest.name}</h2><p>{contest.description}</p>
          <div className="contest-meta"><span><CalendarDays size={15} />{dateTime(contest.startsAt)}</span>
            <span><Users size={15} />{contest.participantCount} joined</span></div>
          <span className="text-link">View contest <ArrowRight size={15} /></span>
        </Link>)}</div> : <Empty icon={CalendarDays} title="No contests in this view" />}
      <Pagination data={data} onChange={setPage} />
    </>}</Resource>
  </div>;
}

function Countdown({ target, label }) {
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const remaining = Math.max(0, Math.ceil((new Date(target).getTime() - now) / 1000));
  const days = Math.floor(remaining / 86400);
  const hours = Math.floor(remaining / 3600) % 24;
  const mins = Math.floor(remaining / 60) % 60;
  const seconds = remaining % 60;
  return <div className="countdown"><Clock3 size={19} /><div><span>{label}</span>
    <strong>{days ? days + 'd ' : ''}{[hours, mins, seconds].map((value) => String(value).padStart(2, '0')).join(':')}</strong></div></div>;
}

export function ContestDetail() {
  const { id } = useParams();
  const { user } = useAuth();
  const { notify } = useApp();
  const location = useLocation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [page, setPage] = useState(0);
  const state = useResource('/contests/' + id);
  const board = useResource(url('/contests/' + id + '/leaderboard', { page }));

  async function register() {
    setBusy(true);
    setError('');
    try {
      await api.post('/contests/' + id + '/register');
      notify('You are registered for this contest.');
      state.refresh();
      board.refresh();
    } catch (failure) { setError(message(failure)); }
    finally { setBusy(false); }
  }

  return <div className="container page"><BackLink to="/contests">All contests</BackLink>
    <Resource state={state}>{({ contest, problems }) => <>
      <PageTitle eyebrow="CONTEST ARENA" title={contest.name} description={contest.description} action={<Badge value={contest.status} />} />
      <div className="panel contest-banner">
        <div className="contest-meta detail"><span><CalendarDays size={17} />{dateTime(contest.startsAt)} to {dateTime(contest.endsAt)}</span>
          <span><Users size={17} />{contest.participantCount} registered</span>
          <span><Trophy size={17} />{contest.problemCount} problems</span></div>
        {contest.status === 'UPCOMING' && <Countdown target={contest.startsAt} label="Starts in" />}
        {contest.status === 'ONGOING' && <Countdown target={contest.endsAt} label="Time remaining" />}
        {contest.status !== 'PAST' && (contest.registered
          ? <span className="badge green"><span className="status-dot" />Registered</span>
          : user ? <button type="button" className="btn primary" disabled={busy} onClick={register}>{busy ? 'Registering…' : 'Register for contest'}</button>
            : <Link className="btn primary" to="/login" state={{ from: location.pathname }}>Log in to register</Link>)}
      </div>
      {error && <ErrorNotice text={error} />}
      <section className="page-section">
        <SectionTitle title="Contest problems" description={contest.penaltyMinutes + ' penalty minutes for each incorrect attempt before a solve, plus elapsed minutes.'} />
        {problems.length ? <div className="panel contest-problem-list">{problems.map((problem) =>
          <div key={problem.problemId}>
            <div><strong>{problem.title}</strong><span>{problem.points} points</span></div><Badge value={problem.difficulty} />
            {problem.published && <Link className="btn secondary small"
              to={contest.status === 'ONGOING' ? '/contests/' + id + '/problems/' + problem.slug : '/problems/' + problem.slug}>Open <ArrowRight size={14} /></Link>}
          </div>)}</div> : <Empty icon={Trophy} title={contest.status === 'ONGOING' ? 'Register to open the problems' : 'Problems unlock when the contest starts'} />}
      </section>
      <section className="page-section">
        <SectionTitle title="Contest leaderboard" /><Resource state={board}>{(data) => <RankingTable data={data} onPage={setPage} contest />}</Resource>
      </section>
    </>}</Resource>
  </div>;
}
