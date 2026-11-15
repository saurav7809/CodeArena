import { ArrowRight, CalendarDays, CheckCircle2, Code2, Compass, Flame, Layers3, Play, Sparkles, Terminal, Users } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useResource } from '../hooks';
import { Badge, dateTime, Empty, Metric, Resource, SectionTitle } from '../components/ui';

const steps = [
  { icon: Layers3, title: 'Pick a challenge', text: 'Filter the library by topic and difficulty. Start where you are and work up.' },
  { icon: Terminal, title: 'Write and run', text: 'Use the same workspace for Python, Java, and C++. Try an example or your own input.' },
  { icon: Sparkles, title: 'Get unstuck', text: 'Ask for a hint, walk through an approach, or get feedback on your code.' },
  { icon: Compass, title: 'Build career skills', text: 'Follow a roadmap for interviews, backend engineering, or full stack development.' },
];

function CodeIllustration() {
  return <div className="hero-visual" aria-label="Illustration of the coding workspace">
    <div className="grid-art" />
    <div className="illustration-window">
      <div className="illustration-bar"><div className="window-dots"><i /><i /><i /></div><span>solution.py</span><Code2 size={16} /></div>
      <div className="illustration-code" aria-hidden="true">
        <div><i>01</i><span className="code-purple">def</span> <span className="code-blue">unique</span>(skus):</div>
        <div><i>02</i>    <span className="code-muted"># Preserve the first occurrence</span></div>
        <div><i>03</i>    seen = <span className="code-blue">set</span>()</div>
        <div><i>04</i>    <span className="code-purple">for</span> sku <span className="code-purple">in</span> skus:</div>
        <div><i>05</i>        <span className="code-purple">if</span> sku <span className="code-purple">not in</span> seen:</div>
        <div><i>06</i>            seen.add(sku)</div>
        <div><i>07</i>            <span className="code-purple">yield</span> sku</div>
      </div>
      <div className="illustration-footer"><span><CheckCircle2 size={15} /> Example output</span><code>CHAIR LAMP RUG</code></div>
    </div>
  </div>;
}

export default function Home() {
  const totals = useResource('/stats/public');
  const problems = useResource('/problems?size=3');
  const contests = useResource('/contests?status=UPCOMING&size=2');
  const firstProblem = problems.data?.items[0];
  return <div className="container home">
    <section className="hero">
      <div className="hero-copy">
        <h1>Master Coding.<br />Compete. <span>Get Better.</span></h1>
        <p>Practice data structures and algorithms, run your code, and build the skills for your next role.</p>
        <div className="button-row hero-actions">
          <Link to={firstProblem ? '/problems/' + firstProblem.slug : '/problems'} className="btn primary"><Play size={17} /> Start Coding</Link>
          <Link to="/problems" className="btn secondary">Explore Problems <ArrowRight size={17} /></Link>
        </div>
        <div className="hero-languages"><Code2 size={15} /><span>Python 3</span><b>·</b><span>Java 17</span><b>·</b><span>C++17</span></div>
      </div>
      <CodeIllustration />
    </section>
    <Resource state={totals}>{(data) => <section className="metric-grid home-metrics" aria-label="Platform statistics">
      <Metric label="Problems" value={data.problems} icon={Layers3} />
      <Metric label="Registered users" value={data.users} icon={Users} />
      <Metric label="Submissions" value={data.submissions} icon={Terminal} />
      <Metric label="Active contests" value={data.activeContests} icon={Flame} />
    </section>}</Resource>
    <section className="home-section">
      <SectionTitle title="Featured problems" description="Start with a challenge from the practice library." to="/problems" linkLabel="Explore the library" />
      <Resource state={problems}>{(data) => <div className="card-grid">{data.items.map((problem) =>
        <Link key={problem.id} className="panel linked-card" to={'/problems/' + problem.slug}>
          <div className="card-top"><Code2 size={20} /><Badge value={problem.difficulty} /></div>
          <h3>{problem.title}</h3><span>{problem.category}</span>
        </Link>)}</div>}</Resource>
    </section>
    <section className="home-section">
      <SectionTitle title="Upcoming contests" description="Reserve a spot and solve under the clock." to="/contests" />
      <Resource state={contests}>{(data) => data.items.length ? <div className="card-grid two">{data.items.map((contest) =>
        <Link className="panel linked-card" to={'/contests/' + contest.id} key={contest.id}>
          <div className="card-top"><CalendarDays size={20} /><Badge value={contest.status} /></div>
          <h3>{contest.name}</h3><span>{dateTime(contest.startsAt)}</span>
        </Link>)}</div> : <Empty icon={CalendarDays} title="Upcoming contests will appear here" />}</Resource>
    </section>
    <section className="home-section">
      <SectionTitle title="How CodeArena works" to="/career" linkLabel="View career roadmaps" />
      <div className="card-grid two">{steps.map(({ icon: Icon, title, text }) =>
        <article className="panel step-card" key={title}>
          <div className="card-top"><Icon size={21} /></div><h3>{title}</h3><p>{text}</p>
        </article>)}</div>
    </section>
  </div>;
}
