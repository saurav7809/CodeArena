import { Braces, Check, Clipboard, Code2, FileText, FlaskConical, Play, RotateCcw, Send, Terminal } from 'lucide-react';
import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, apiOrigin, message } from '../api';
import SubmissionResults, { isPending } from '../components/SubmissionResults';
import { BackLink, Badge, Empty, ErrorNotice, LANGUAGE_NAMES, Loading, Resource } from '../components/ui';
import { useApp, useAuth } from '../context';
import { useResource } from '../hooks';

const Editor = lazy(() => import('../components/Editor'));
const FILENAMES = { PYTHON: 'main.py', JAVA: 'Main.java', CPP: 'main.cpp' };

function Example({ test, index }) {
  return <div className="example">
    <h3>Example {index + 1}</h3>
    <div className="example-block"><span>Input</span><pre>{test.input || '(empty)'}</pre></div>
    <div className="example-block"><span>Expected output</span><pre>{test.expectedOutput || '(empty)'}</pre></div>
  </div>;
}

export default function Workspace() {
  const { slug, id } = useParams();
  const state = useResource('/problems/slug/' + encodeURIComponent(slug));
  const contest = useResource(id ? '/contests/' + id : null);
  return <div className="container workspace-page">
    <BackLink to={id ? '/contests/' + id : '/problems'}>{id ? 'Back to contest' : 'All problems'}</BackLink>
    {id ? <Resource state={contest}>{(data) => data.contest.status === 'ONGOING' && data.contest.registered
      && data.problems.some((item) => item.slug === slug)
      ? <Resource state={state}>{(problem) => <Practice key={id + '/' + slug} detail={problem} contest={data.contest} />}</Resource>
      : <Empty icon={Code2} title="This contest workspace is closed"><Link className="btn secondary" to={'/contests/' + id}>Open contest details</Link></Empty>}
    </Resource> : <Resource state={state}>{(problem) => <Practice key={slug} detail={problem} />}</Resource>}
  </div>;
}

function Practice({ detail, contest }) {
  const { user } = useAuth();
  const { theme, notify } = useApp();
  const location = useLocation();
  const [language, setLanguage] = useState('PYTHON');
  const [drafts, setDrafts] = useState({});
  const [height, setHeight] = useState(320);
  const [tab, setTab] = useState('tests');
  const [customInput, setCustomInput] = useState('');
  const [customRun, setCustomRun] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState(null);
  const [retry, setRetry] = useState(0);
  const [pollFailed, setPollFailed] = useState(false);
  const active = useRef(true);
  const { problem } = detail;
  const draftKey = 'arena-draft-' + (user?.id || 'guest') + '-' + problem.id + '-' + language;
  const code = drafts[draftKey] ?? localStorage.getItem(draftKey) ?? detail.starterCode[language];
  const pendingId = isPending(result?.submission) ? result.submission.id : null;
  const busy = submitting || Boolean(pendingId);

  useEffect(() => {
    active.current = true;
    return () => { active.current = false; };
  }, []);
  useEffect(() => {
    if (!pendingId) return;
    const controller = new AbortController();
    let timer;
    async function poll() {
      try {
        const { data } = await api.get('/submissions/' + pendingId, { signal: controller.signal });
        if (controller.signal.aborted) return;
        setResult(data);
        if (isPending(data.submission)) timer = setTimeout(poll, 650);
      } catch (failure) {
        if (controller.signal.aborted) return;
        setError(message(failure));
        setPollFailed(true);
      }
    }
    timer = setTimeout(poll, 400);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [pendingId, retry]);

  function edit(text) {
    setDrafts((previous) => ({ ...previous, [draftKey]: text }));
    localStorage.setItem(draftKey, text);
  }

  async function copy() {
    try { await navigator.clipboard.writeText(code); notify('Code copied.'); }
    catch { notify('Clipboard access is unavailable.', 'warning'); }
  }

  async function queue(action, useCustom = false) {
    setSubmitting(true);
    setError('');
    setPollFailed(false);
    try {
      const { data } = await api.post('/submissions/' + action, {
        problemId: problem.id, language, code,
        contestId: contest?.id ?? null, customInput: useCustom ? customInput : null,
      });
      if (!active.current) return;
      setCustomRun(useCustom);
      setResult({ submission: data, diagnostic: null, cases: [] });
      setTab('output');
    } catch (failure) {
      if (active.current) setError(message(failure));
    } finally {
      if (active.current) setSubmitting(false);
    }
  }

  return <>
    <div className="workspace-title"><div><p className="eyebrow">{contest?.name || 'PRACTICE ARENA'}</p>
      <h1>{problem.title}</h1>
      <div className="button-row"><Badge value={problem.difficulty} /><span className="topic-chip">{problem.category}</span>
        {problem.solved && <span className="badge green"><Check size={13} />Solved</span>}</div>
    </div>{user && <Link to={'/submissions?problemId=' + problem.id} className="btn secondary small">My attempts</Link>}</div>
    <div className="workspace-grid">
      <article className="panel statement">
        <div className="panel-bar"><FileText size={17} /><strong>Problem statement</strong></div>
        <div className="statement-body">
          <p className="statement-description">{detail.description}</p>
          <h3>Input format</h3><p>{detail.inputFormat}</p>
          <h3>Output format</h3><p>{detail.outputFormat}</p>
          <h3>Constraints</h3><p>{detail.constraints}</p>
          {detail.examples.map((test, index) => <Example key={test.position} test={test} index={index} />)}
          {detail.explanation && <><h3>Explanation</h3><p>{detail.explanation}</p></>}
          <div className="statement-meta"><span>{detail.timeLimitMs} ms per case</span><span>{detail.memoryLimitMb} MB memory</span>
            <span>{problem.accepted} accepted / {problem.submissions} submissions</span></div>
        </div>
      </article>
      <section className="workspace-tools">
        <div className="panel code-panel">
          <div className="panel-bar code-toolbar">
            <div className="button-row"><Braces size={17} /><strong>{FILENAMES[language]}</strong></div>
            <div className="button-row">
              <select aria-label="Programming language" value={language} onChange={(event) => setLanguage(event.target.value)}>
                {Object.entries(LANGUAGE_NAMES).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
              </select>
              <button type="button" className="icon-button" aria-label="Copy code" title="Copy code" onClick={copy}><Clipboard size={16} /></button>
              <button type="button" className="icon-button" aria-label="Reset starter code" title="Reset starter code"
                onClick={() => edit(detail.starterCode[language])}><RotateCcw size={16} /></button>
            </div>
          </div>
          <Suspense fallback={<div style={{ height }}><Loading text="Starting the editor…" /></div>}>
            <Editor key={draftKey} value={code} language={language} theme={theme} height={height} onChange={edit} />
          </Suspense>
          <div className="editor-actions">
            <label className="resize-control">Editor height<input type="range" aria-label="Editor height" min="200" max="650" value={height}
              onChange={(event) => setHeight(Number(event.target.value))} /></label>
            {user ? <div className="button-row">
              <button type="button" className="btn secondary small" disabled={busy || !code.trim()} onClick={() => queue('run')}><Play size={15} />Run samples</button>
              <button type="button" className="btn primary small" disabled={busy || !code.trim()} onClick={() => queue('submit')}><Send size={15} />Submit</button>
            </div> : <Link className="btn primary small" to="/login" state={{ from: location.pathname }}>Log in to run and submit</Link>}
          </div>
          {apiOrigin && <p className="small-text judge-notice">Running or submitting sends your code and test inputs to the hosted judge.</p>}
        </div>
        <div className="panel test-panel">
          <div className="panel-bar tabs test-tabs" aria-label="Editor results panel">
            {[['tests', 'Test cases', FlaskConical], ['custom', 'Custom input', FileText], ['output', 'Output', Terminal]].map(([key, label, Icon]) =>
              <button key={key} type="button" className={tab === key ? 'active' : ''} aria-pressed={tab === key} onClick={() => setTab(key)}>
                <Icon size={15} />{label}</button>)}
          </div>
          <div className="test-body">
            {error && <ErrorNotice text={error} retry={pollFailed ? () => { setError(''); setPollFailed(false); setRetry(retry + 1); } : undefined} />}
            {tab === 'tests' && <div className="sample-grid">{detail.examples.map((item, index) => <Example test={item} key={item.position} index={index} />)}</div>}
            {tab === 'custom' && <div className="form-stack">
              <label>Standard input<textarea value={customInput} onChange={(event) => setCustomInput(event.target.value)} rows={4} maxLength={8192}
                className="mono" placeholder="Enter input in the problem’s format." /></label>
              {user ? <button type="button" className="btn secondary small" onClick={() => queue('run', true)} disabled={busy || !code.trim()}>
                <Play size={15} />Run custom input</button> : <Link to="/login" state={{ from: location.pathname }} className="text-link">Log in to use custom input</Link>}
            </div>}
            {tab === 'output' && <SubmissionResults detail={result} custom={customRun} />}
          </div>
        </div>
      </section>
    </div>
  </>;
}
