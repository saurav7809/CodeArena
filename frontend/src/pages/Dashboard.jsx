import { Activity, Award, BarChart3, CheckCircle2, Code2, Target, Trophy, Zap } from 'lucide-react';
import { Link, NavLink } from 'react-router-dom';
import { ActivityGrid, Breakdown, TrendChart } from '../components/Activity';
import { Badge, dateTime, Empty, LANGUAGE_NAMES, Metric, PageTitle, Resource, SectionTitle } from '../components/ui';
import { useAuth } from '../context';
import { useResource } from '../hooks';
import { SubmissionTable } from './Submissions';

export function UserTabs() {
  return <nav className="tabs user-tabs" aria-label="Account navigation">
    <NavLink to="/dashboard">Overview</NavLink><NavLink to="/submissions">Submissions</NavLink>
    <NavLink to="/analytics">Analytics</NavLink><NavLink to="/achievements">Achievements</NavLink><NavLink to="/profile">Profile</NavLink>
  </nav>;
}

function Difficulty({ counts }) {
  return <Breakdown items={[{ label: 'Easy', count: counts.easy }, { label: 'Medium', count: counts.medium }, { label: 'Hard', count: counts.hard }]} />;
}

function Achievements({ awards, showLocked = true }) {
  const items = showLocked ? awards : awards.filter((item) => item.earnedAt).slice(0, 3);
  if (!items.length) return <Empty icon={Award} title="Your first badge is within reach">Submit an accepted solution to unlock it.</Empty>;
  return <div className="card-grid">{items.map((award) => <article className={'panel award-card ' + (award.earnedAt ? 'earned' : '')} key={award.code}>
    <div className="card-top"><div className="feature-icon"><Award size={20} /></div>
      <span className={'badge ' + (award.earnedAt ? 'green' : 'muted')}>{award.earnedAt ? 'Earned' : 'Locked'}</span></div>
    <h3>{award.name}</h3><p>{award.description}</p>
    {award.earnedAt && <span className="small-text">{dateTime(award.earnedAt)}</span>}
  </article>)}</div>;
}

export default function Dashboard({ section = 'overview' }) {
  const { user } = useAuth();
  const state = useResource('/users/me/stats');
  const titles = {
    overview: 'Welcome, ' + user.fullName.split(' ')[0],
    analytics: 'Coding analytics',
    achievements: 'Achievements',
  };
  return <div className="container page">
    <PageTitle eyebrow="MY ARENA" title={titles[section]} description="Review your practice and choose the next challenge."
      action={<Link className="btn primary" to="/problems"><Code2 size={16} />Explore problems</Link>} />
    <UserTabs />
    <Resource state={state}>{(data) => {
      if (section === 'achievements') return <Achievements awards={data.achievements} />;
      if (section === 'analytics') {
        let solved = 0;
        const cumulative = data.solvedOverTime.map((day) => ({ ...day, count: (solved += day.count) }));
        return <div className="card-grid two">
          <section className="panel padded"><SectionTitle title="Problems solved over time" /><TrendChart data={cumulative} label="Solved" chartId="solved" /></section>
          <section className="panel padded"><SectionTitle title="Acceptance rate" /><TrendChart data={data.acceptanceOverTime} label="Acceptance %" chartId="acceptance" maximum={100} /></section>
          <section className="panel padded"><SectionTitle title="Submission activity" /><TrendChart data={data.activity} label="Submissions" chartId="activity" /></section>
          <section className="panel padded"><SectionTitle title="Difficulty distribution" /><Difficulty counts={data.counts} /></section>
          <section className="panel padded"><SectionTitle title="Language distribution" />
            <Breakdown items={Object.entries(LANGUAGE_NAMES).map(([key, label]) =>
              ({ label, count: data.languages.find((item) => item.label === key)?.count || 0 }))} /></section>
        </div>;
      }
      return <>
        <div className="metric-grid">
          <Metric label="Problems solved" value={data.counts.solved} icon={CheckCircle2} />
          <Metric label="Submissions" value={data.counts.submissions} icon={Zap} />
          <Metric label="Acceptance rate" value={data.counts.acceptance + '%'} icon={Target} />
          <Metric label="Global rank" value={data.counts.rank ? '#' + data.counts.rank : '—'} icon={Trophy} />
        </div>
        <div className="card-grid two page-section">
          <section className="panel padded"><SectionTitle title="Coding activity" description="The last 91 days in UTC." /><ActivityGrid activity={data.activity} /></section>
          <section className="panel padded"><SectionTitle title="Problem-solving progress" /><Difficulty counts={data.counts} /></section>
        </div>
        <section className="page-section"><SectionTitle title="Recent submissions" to="/submissions" /><SubmissionTable items={data.recent} /></section>
        <section className="page-section"><SectionTitle title="Recommended problems" to="/problems" />
          {data.recommended.length ? <div className="card-grid">{data.recommended.map((problem) =>
            <Link className="panel linked-card" to={'/problems/' + problem.slug} key={problem.id}>
              <div className="card-top"><Code2 size={18} /><Badge value={problem.difficulty} /></div>
              <h3>{problem.title}</h3><span>{problem.category}</span>
            </Link>)}</div> : <Empty icon={CheckCircle2} title="You solved every published problem" />}
        </section>
        <section className="page-section"><SectionTitle title="Contest participation" to="/contests" />
          {data.contests.length ? <div className="card-grid">{data.contests.map((contest) =>
            <Link className="panel linked-card" to={'/contests/' + contest.id} key={contest.id}>
              <div className="card-top"><Trophy size={18} /><Badge value={contest.status} /></div><h3>{contest.name}</h3>
            </Link>)}</div> : <Empty icon={Trophy} title="Ready for your first contest"><Link className="btn secondary small" to="/contests">Browse contests</Link></Empty>}
        </section>
        <section className="page-section"><SectionTitle title="Recent achievements" to="/achievements" /><Achievements awards={data.achievements} showLocked={false} /></section>
      </>;
    }}</Resource>
  </div>;
}
