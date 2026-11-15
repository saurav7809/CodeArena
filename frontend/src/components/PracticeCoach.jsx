import { BookOpen, Lightbulb, ScanLine } from 'lucide-react';
import { useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAiRequest, useResource } from '../hooks';
import { ErrorNotice, LANGUAGE_NAMES, Loading, Resource } from './ui';

const actions = [
  { id: 'HINT', label: 'Hints', icon: Lightbulb },
  { id: 'EXPLANATION', label: 'Explanation', icon: BookOpen },
  { id: 'REVIEW', label: 'Code review', icon: ScanLine },
];
const hintTitles = ['Spot the pattern', 'Choose a technique', 'Plan the steps'];

export default function PracticeCoach({ user, problemId, language, code }) {
  const location = useLocation();
  const status = useResource(user ? '/ai/status' : null);
  if (!user) return <div className="form-stack">
    <p>Sign in to get hints, explanations, and feedback on your code.</p>
    <Link to="/login" state={{ from: location.pathname }} className="btn secondary small">Log in to use the AI coach</Link>
  </div>;
  return <Resource state={status}>{({ enabled }) => enabled
    ? <CoachActions problemId={problemId} language={language} code={code} />
    : <p className="muted">The AI coach is currently unavailable. You can still run samples and submit your solution.</p>}
  </Resource>;
}

function CoachActions({ problemId, language, code }) {
  const [mode, setMode] = useState('HINT');
  const [hints, setHints] = useState([]);
  const [explanation, setExplanation] = useState('');
  const [review, setReview] = useState(null);
  const { ask, busy, clearError, error } = useAiRequest('/ai/practice');
  const nextHint = hints.length + 1;
  const draftChanged = review && (language !== review.language || code !== review.code);
  const invalidDraft = mode === 'REVIEW' && (!code.trim() || code.length > 24000);

  async function generate() {
    const payload = { problemId, action: mode };
    if (mode === 'HINT') payload.hintLevel = nextHint;
    if (mode === 'REVIEW') Object.assign(payload, { language, code });
    const text = await ask(payload);
    if (!text) return;
    if (mode === 'HINT') setHints((previous) => [...previous, text]);
    if (mode === 'EXPLANATION') setExplanation(text);
    if (mode === 'REVIEW') setReview({ text, language, code });
  }

  return <div className="coach-panel">
    <div className="button-row" aria-label="AI coaching actions">
      {actions.map(({ id, label, icon: Icon }) => <button key={id} type="button"
        className={'btn small ' + (mode === id ? 'primary' : 'secondary')} aria-pressed={mode === id} disabled={busy}
        onClick={() => { setMode(id); clearError(); }}><Icon size={15} />{label}</button>)}
    </div>
    {mode === 'HINT' && <>
      {!hints.length && <p>Start with a small clue and reveal more when you’re ready.</p>}
      {hints.map((text, index) => <div key={index} className="coach-reply">
        <h3>{hintTitles[index]}</h3><p>{text}</p>
      </div>)}
    </>}
    {mode === 'EXPLANATION' && (explanation ? <div className="coach-reply"><p>{explanation}</p></div>
      : <p>See an approach, a sample walkthrough, and the time and space complexity.</p>)}
    {mode === 'REVIEW' && <>
      <p>Code review sends your current draft to OpenAI.</p>
      {code.length > 24000 && <p className="small-text">Shorten the draft to 24,000 characters to request a review.</p>}
      {review && <div className="coach-reply"><h3>{LANGUAGE_NAMES[review.language]} review</h3>
        {draftChanged && <p className="coach-note">The editor has changed since this review.</p>}
        <p>{review.text}</p>
      </div>}
    </>}
    {busy && <Loading text="Waiting for the AI coach…" />}
    {error && <ErrorNotice text={error} />}
    {(mode !== 'HINT' || nextHint <= hintTitles.length) && <div>
      <button type="button" className="btn secondary small" disabled={busy || invalidDraft} onClick={generate}>
        {mode === 'HINT' && (hints.length ? 'Get next hint' : 'Get a hint')}
        {mode === 'EXPLANATION' && (explanation ? 'Regenerate explanation' : 'Explain this problem')}
        {mode === 'REVIEW' && 'Review current code'}
      </button>
    </div>}
  </div>;
}
