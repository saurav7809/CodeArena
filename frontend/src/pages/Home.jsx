import { ArrowRight, ArrowUpRight, CalendarDays, CheckCircle2, Code2, Compass, FileCode2, Flame, Layers3, Sparkles, Terminal, Users } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useResource } from '../hooks';
import { Badge, dateTime, Empty, LANGUAGE_NAMES, Metric, number, Resource, SectionTitle } from '../components/ui';

const steps = [
  { icon: Layers3, title: 'Pick a challenge', text: 'Choose a problem by topic or difficulty.' },
  { icon: Terminal, title: 'Write and run', text: 'Test your solution against examples or provide your own input.' },
  { icon: Sparkles, title: 'Review your solution', text: 'Compare your output with the expected result or get a hint from the AI coach.' },
  { icon: Compass, title: 'Build career skills', text: 'Follow a roadmap for interviews, backend engineering, or full stack development.' },
];

const previewLines = [
  <><span className="syntax-keyword">def</span> <span className="syntax-function">unique_skus</span>(items):</>,
  <>{'    '}<span className="syntax-keyword">return</span> <span className="syntax-function">dict</span>.fromkeys(items)</>,
  '',
  <>items = <span className="syntax-string">"CHAIR LAMP CHAIR RUG"</span></>,
  <>result = unique_skus(items.split())</>,
  <><span className="syntax-function">print</span>(*result)</>,
];

function WorkspacePreview() {
  return <figure className="workspace-preview" aria-label="Coding workspace example">
    <figcaption className="preview-bar"><Terminal size={16} />Workspace preview</figcaption>
    <div className="preview-toolbar">
      <span className="preview-file"><FileCode2 size={15} />solution.py</span>
      <span>{LANGUAGE_NAMES.PYTHON}</span>
    </div>
    <pre className="preview-code"><code>{previewLines.map((line, index) => <span className="preview-line" key={index}>
      <span className="preview-line-number" aria-hidden="true">{index + 1}</span>{line}
    </span>)}</code></pre>
    <div className="preview-result"><span><CheckCircle2 size={16} />Sample output</span><code>CHAIR LAMP RUG</code></div>
  </figure>;
}

export default function Home() {
  const totals = useResource('/stats/public');
  const problems = useResource('/problems?size=3');
  const contests = useResource('/contests?status=UPCOMING&size=2');
  const firstProblem = problems.data?.items[0];

  return <div className="home">
    <section className="hero" aria-labelledby="home-title">
      <div className="container hero-inner">
        <div className="hero-copy">
          <h1 id="home-title">Master Coding.<br />Compete. <span>Get Better.</span></h1>
          <p>Sharpen your problem-solving skills with coding challenges, prepare for technical interviews, and compete in timed contests.</p>
          <div className="button-row hero-actions">
            <Link to={firstProblem ? '/problems/' + firstProblem.slug : '/problems'} className="btn primary">Start Coding <ArrowRight size={17} /></Link>
            <Link to="/problems" className="btn secondary">Explore Problems</Link>
          </div>
          <div className="hero-languages"><Code2 size={17} aria-hidden="true" />
            <ul aria-label="Supported languages">{Object.values(LANGUAGE_NAMES).map((name) => <li key={name}>{name}</li>)}</ul>
          </div>
        </div>
        <div className="hero-preview"><WorkspacePreview /></div>
      </div>
    </section>
    <div className="container home-content">
      <Resource state={totals}>{(data) => <section className="panel metric-grid home-metrics" aria-label="Platform statistics">
        <Metric label="Problems" value={number(data.problems)} icon={Layers3} />
        <Metric label="Registered users" value={number(data.users)} icon={Users} />
        <Metric label="Submissions" value={number(data.submissions)} icon={Terminal} />
        <Metric label="Active contests" value={number(data.activeContests)} icon={Flame} />
      </section>}</Resource>
      <section className="home-section">
        <SectionTitle title="Featured problems" to="/problems" linkLabel="View all problems" />
        <Resource state={problems}>{(data) => data.items.length ? <div className="card-grid featured-problems">{data.items.map((problem) =>
          <Link key={problem.id} className="panel featured-problem" to={'/problems/' + problem.slug}>
            <div className="featured-problem-top"><span className="feature-icon"><Code2 size={19} /></span><Badge value={problem.difficulty} /></div>
            <h3>{problem.title}</h3>
            <span className="featured-problem-meta">{problem.category}<ArrowUpRight size={17} /></span>
          </Link>)}</div> : <Empty icon={Code2} title="No problems published yet" />}</Resource>
      </section>
      <div className="home-lower">
        <section className="home-section">
          <SectionTitle title="How CodeArena works" to="/career" linkLabel="Career roadmaps" />
          <ol className="panel step-list" role="list">{steps.map(({ icon: Icon, title, text }) =>
            <li key={title}><span className="feature-icon"><Icon size={17} /></span><div><h3>{title}</h3><p>{text}</p></div></li>
          )}</ol>
        </section>
        <section className="home-section">
          <SectionTitle title="Upcoming contests" to="/contests" linkLabel="All contests" />
          <Resource state={contests}>{(data) => data.items.length ? <div className="home-contests">{data.items.map((contest) =>
            <Link className="panel linked-card" to={'/contests/' + contest.id} key={contest.id}>
              <div className="card-top"><CalendarDays size={20} /><Badge value={contest.status} /></div>
              <h3>{contest.name}</h3><span>{dateTime(contest.startsAt)}</span>
            </Link>)}</div> : <Empty icon={CalendarDays} title="No contests scheduled" />}</Resource>
        </section>
      </div>
    </div>
  </div>;
}
