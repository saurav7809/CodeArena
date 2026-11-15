import { ArrowRight, CheckCircle2, ChevronRight, Compass, Map as MapIcon, PanelsTopLeft, Server, Sparkles, Target, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, message } from '../api';
import { dateTime, Empty, ErrorNotice, Loading, Resource } from '../components/ui';
import { useAuth } from '../context';
import { useAiRequest, useResource } from '../hooks';

const trackIcons = { interview: Target, backend: Server, 'full-stack': PanelsTopLeft };

export default function Career() {
  const { user, authLoading } = useAuth();
  const { trackId } = useParams();
  return <div className="container page career-page">
    <div className="page-title"><div><h1>Career roadmaps</h1>
      <p>Choose a track, practice the skills it calls for, and track what you’ve learned.</p>
    </div></div>
    {authLoading ? <Loading /> : <Roadmaps key={user?.id ?? 'guest'} user={user} trackId={trackId} />}
  </div>;
}

function Roadmaps({ user, trackId }) {
  const state = useResource('/career/roadmaps');
  return <Resource state={state}>{(data) => <RoadmapContent user={user} trackId={trackId} data={data} />}</Resource>;
}

function skillsFor(track) {
  return track.milestones.flatMap((milestone) => milestone.skills);
}

function RoadmapContent({ user, trackId, data }) {
  const location = useLocation();
  const [completed, setCompleted] = useState(() => new Set(data.completedSkills));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const problems = new Map(data.problems.map((problem) => [problem.slug, problem]));
  const track = trackId ? data.tracks.find((item) => item.id === trackId) : data.tracks[0];
  if (!track) return <Empty icon={MapIcon} title="Roadmap not found">
    <Link className="btn secondary" to="/career">View career roadmaps</Link>
  </Empty>;
  const skills = skillsFor(track);
  const count = skills.filter((skill) => completed.has(skill.id)).length;

  async function update(skillId, complete) {
    setSaving(true);
    setError('');
    try {
      await api.put('/career/skills/' + encodeURIComponent(skillId), { completed: complete });
      setCompleted((previous) => {
        const next = new Set(previous);
        if (complete) next.add(skillId);
        else next.delete(skillId);
        return next;
      });
    } catch (failure) {
      setError(message(failure));
    } finally {
      setSaving(false);
    }
  }

  return <div className="career-layout">
    <aside className="career-sidebar">
      <nav className="career-tracks" aria-label="Career tracks">
        {data.tracks.map((item) => {
          const Icon = trackIcons[item.id] || Compass;
          const active = item.id === track.id;
          const items = skillsFor(item);
          const done = items.filter((skill) => completed.has(skill.id)).length;
          return <Link to={'/career/' + item.id} key={item.id} className={'panel career-track' + (active ? ' selected' : '')}
            aria-current={active ? 'page' : undefined}>
            <span className="feature-icon"><Icon size={18} /></span>
            <span><strong>{item.title}</strong>
              <small>{user ? done + ' of ' + items.length + ' skills' : items.length + ' skills'}</small></span>
            <ChevronRight size={16} />
          </Link>;
        })}
      </nav>
      <CareerCoach key={track.id} track={track} user={user} />
    </aside>
    <section aria-labelledby="roadmap-title" className="career-roadmap">
      <header className="panel career-overview">
        <h2 id="roadmap-title">{track.title}</h2>
        <p>{track.description}</p>
        {user ? <div className="career-progress">
          <label htmlFor="skill-progress">{count} of {skills.length} skills complete</label>
          <progress id="skill-progress" value={count} max={skills.length} />
        </div> : <Link to="/login" state={{ from: location.pathname }} className="text-link">
          Log in to track your skills <ArrowRight size={14} />
        </Link>}
      </header>
      {error && <ErrorNotice text={error} />}
      <ol className="career-milestones">
        {track.milestones.map((milestone, index) => <li key={milestone.title} className="career-milestone">
          <span className="milestone-index" aria-hidden="true">{index + 1}</span>
          <section className="panel milestone-card">
            <header><h3>{milestone.title}</h3><p>{milestone.description}</p></header>
            <ul>
              {milestone.skills.map((skill) => <li key={skill.id} className="career-skill">
                <div>
                  <h4>{skill.title}</h4>
                  <p>{skill.description}</p>
                  <div className="career-practice">
                    {skill.problems.map((slug) => {
                      const problem = problems.get(slug);
                      return problem && <Link key={slug} className="career-problem" to={'/problems/' + slug}>
                        {problem.solved && <><CheckCircle2 size={13} /><span className="sr-only">Solved </span></>}
                        {problem.title}<ChevronRight size={13} />
                      </Link>;
                    })}
                  </div>
                </div>
                {user && <label className="checkbox-label skill-check">
                  <input type="checkbox" checked={completed.has(skill.id)} disabled={saving}
                    aria-label={'Complete ' + skill.title} onChange={(event) => update(skill.id, event.target.checked)} />
                  <span>Done</span>
                </label>}
              </li>)}
            </ul>
          </section>
        </li>)}
      </ol>
    </section>
  </div>;
}

function CareerCoach({ user, track }) {
  const location = useLocation();
  const status = useResource(user ? '/ai/status' : null);
  const saved = useResource(user ? '/career/plans/' + track.id : null);
  return <section className="panel padded career-coach" aria-labelledby="career-coach-title">
    <h2 id="career-coach-title"><Sparkles size={18} /> AI career guidance</h2>
    {!user ? <>
      <p>Get a four-week plan for your experience and schedule.</p>
      <Link to="/login" state={{ from: location.pathname }} className="text-link">Log in to get a plan <ArrowRight size={14} /></Link>
    </> : <Resource state={saved}>{(plan) => <CareerCoachForm track={track} status={status} initial={plan} />}</Resource>}
  </section>;
}

function CareerCoachForm({ track, status, initial }) {
  const [plan, setPlan] = useState(initial);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState('');
  const { ask, busy, error } = useAiRequest('/ai/career');

  async function generate(event) {
    event.preventDefault();
    const fields = new FormData(event.currentTarget);
    setDeleteError('');
    const text = await ask({ track: track.id, experience: fields.get('experience'),
      weeklyHours: Number(fields.get('hours')), goal: fields.get('goal'), save: true });
    if (text) setPlan({ text, generatedAt: new Date().toISOString() });
  }

  async function deletePlan() {
    setDeleting(true);
    setDeleteError('');
    try {
      await api.delete('/career/plans/' + track.id);
      setPlan({ text: null, generatedAt: null });
    } catch (failure) {
      setDeleteError(message(failure));
    } finally {
      setDeleting(false);
    }
  }

  return <>
    <Resource state={status}>{({ enabled }) => enabled ? <form className="form-stack" onSubmit={generate}>
      <p>Generate and save a four-week plan for {track.title.toLowerCase()}.</p>
      <label>Experience
        <select name="experience" disabled={busy}>
          <option value="BEGINNER">Just starting</option>
          <option value="SOME_PRACTICE">Some practice</option>
          <option value="PROJECT_EXPERIENCE">Built projects</option>
        </select>
      </label>
      <label>Hours per week<input type="number" name="hours" min="1" max="40" defaultValue="5" required disabled={busy} /></label>
      <label>Goal (optional)<textarea name="goal" rows={3} maxLength={600}
        placeholder="e.g. Prepare for my first internship interview" disabled={busy} /></label>
      <button className="btn primary small" type="submit" disabled={busy || deleting}>
        {plan.text ? 'Replace my plan' : 'Generate my plan'} <ArrowRight size={14} />
      </button>
    </form> : <p>Plan generation is currently unavailable. You can continue using the roadmap and tracking your skills.</p>}</Resource>
    {busy && <Loading text="Writing your plan…" />}
    {error && <ErrorNotice text={error} />}
    {deleteError && <ErrorNotice text={deleteError} />}
    {plan.text && <div className="coach-reply">
      <h3>Your four-week plan</h3>
      <small className="small-text">Saved {dateTime(plan.generatedAt)}</small>
      <p aria-live="polite">{plan.text}</p>
      <button className="btn secondary small" type="button" onClick={deletePlan} disabled={busy || deleting}>
        <Trash2 size={14} />{deleting ? 'Removing…' : 'Remove saved plan'}
      </button>
    </div>}
  </>;
}
