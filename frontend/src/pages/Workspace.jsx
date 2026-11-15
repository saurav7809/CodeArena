import { Braces, Check, Clipboard, Code2, FileText, FlaskConical, Play, RotateCcw, Send, Sparkles, Terminal } from 'lucide-react';
import { lazy, Suspense, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { apiOrigin } from '../api';
import PracticeCoach from '../components/PracticeCoach';
import SubmissionResults from '../components/SubmissionResults';
import { BackLink, Badge, Empty, ErrorNotice, LANGUAGE_NAMES, Loading, Resource } from '../components/ui';
import { useApp, useAuth } from '../context';
import { useResource } from '../hooks';
import { storage } from '../storage';
import { useExecution } from '../useExecution';

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
  const { user, authLoading } = useAuth();
  const { slug, id } = useParams();
  const state = useResource('/problems/slug/' + encodeURIComponent(slug));
  const contest = useResource(id ? '/contests/' + id : null);
  const scope = (user?.id ?? 'guest') + '/' + (id ?? 'practice') + '/' + slug;
  return <div className="container workspace-page">
    <BackLink to={id ? '/contests/' + id : '/problems'}>{id ? 'Back to contest' : 'All problems'}</BackLink>
    {authLoading ? <Loading /> : id ? <Resource state={contest}>{(data) => data.contest.status === 'ONGOING' && data.contest.registered
      && data.problems.some((item) => item.slug === slug)
      ? <Resource state={state}>{(problem) => <Practice key={scope} detail={problem} contest={data.contest} />}</Resource>
      : <Empty icon={Code2} title="This contest workspace is closed"><Link className="btn secondary" to={'/contests/' + id}>Open contest details</Link></Empty>}
    </Resource> : <Resource state={state}>{(problem) => <Practice key={scope} detail={problem} />}</Resource>}
  </div>;
}

function Practice({ detail, contest }) {
  const { user } = useAuth();
  const { theme, notify } = useApp();
  const location = useLocation();
  const [language, setLanguage] = useState('PYTHON');
  const [drafts, setDrafts] = useState({});
  const [solved, setSolved] = useState(detail.problem.solved);
  const [height, setHeight] = useState(320);
  const [tab, setTab] = useState(null);
  const [openedCoach, setOpenedCoach] = useState(false);
  const [customInput, setCustomInput] = useState('');
  const warnedAboutStorage = useRef(false);
  const { problem } = detail;
  const draftKey = 'arena-draft-' + (user?.id || 'guest') + '-' + problem.id + '-' + language;
  const code = drafts[draftKey] ?? storage.local.get(draftKey) ?? detail.starterCode[language];
  const execution = useExecution(user ? 'arena-execution-' + user.id + '-' + problem.id + '-' + (contest?.id ?? 'practice') : null);
  const currentTab = tab ?? (execution.hasJob ? 'output' : 'tests');
  const codeSizeExceeded = code.length > 24000;
  const canRun = !execution.busy && !codeSizeExceeded && Boolean(code.trim());
  const accepted = execution.result?.submission.mode === 'SUBMIT' && execution.result.submission.verdict === 'ACCEPTED';
  if (accepted && !solved) setSolved(true);

  function edit(text) {
    setDrafts((previous) => ({ ...previous, [draftKey]: text }));
    if (!storage.local.set(draftKey, text) && !warnedAboutStorage.current) {
      warnedAboutStorage.current = true;
      notify('This browser could not save your draft. Copy it before leaving the page.', 'warning');
    }
  }

  async function copy() {
    try { await navigator.clipboard.writeText(code); notify('Code copied.'); }
    catch { notify('Clipboard access is unavailable.', 'warning'); }
  }

  async function queue(action, useCustom = false) {
    if (await execution.queue(action, {
      problemId: problem.id, language, code,
      contestId: contest?.id ?? null, customInput: useCustom ? customInput : null,
    })) setTab('output');
  }

  return <>
    <div className="workspace-title"><div><p className="eyebrow">{contest?.name || 'PRACTICE ARENA'}</p>
      <h1>{problem.title}</h1>
      <div className="button-row"><Badge value={problem.difficulty} /><span className="topic-chip">{problem.category}</span>
        {solved && <span className="badge green"><Check size={13} />Solved</span>}</div>
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
              <button type="button" className="btn secondary small" disabled={!canRun} onClick={() => queue('run')}><Play size={15} />Run samples</button>
              <button type="button" className="btn primary small" disabled={!canRun} onClick={() => queue('submit')}><Send size={15} />Submit</button>
            </div> : <Link className="btn primary small" to="/login" state={{ from: location.pathname }}>Log in to run and submit</Link>}
          </div>
          {codeSizeExceeded && <p className="small-text judge-notice" role="status">Shorten the code to 24,000 characters to run it.</p>}
          {apiOrigin && <p className="small-text judge-notice">Running or submitting sends your code and test inputs to the hosted judge.</p>}
        </div>
        <div className="panel test-panel">
          <div className="panel-bar tabs test-tabs" aria-label="Editor results panel">
            {[['tests', 'Test cases', FlaskConical], ['custom', 'Custom input', FileText], ['output', 'Output', Terminal],
              ...(!contest && detail.coachingEnabled !== false ? [['coach', 'AI coach', Sparkles]] : [])].map(([key, label, Icon]) =>
              <button key={key} type="button" className={currentTab === key ? 'active' : ''} aria-pressed={currentTab === key} onClick={() => {
                setTab(key);
                if (key === 'coach') setOpenedCoach(true);
              }}>
                <Icon size={15} />{label}</button>)}
          </div>
          <div className="test-body">
            {execution.error && <ErrorNotice text={execution.error} retry={execution.needsRetry
              ? async () => { if (await execution.retry()) setTab('output'); } : undefined} />}
            {currentTab === 'tests' && <div className="sample-grid">{detail.examples.map((item, index) => <Example test={item} key={item.position} index={index} />)}</div>}
            {currentTab === 'custom' && <div className="form-stack">
              <label>Standard input<textarea value={customInput} onChange={(event) => setCustomInput(event.target.value)} rows={4} maxLength={8192}
                className="mono" placeholder="Enter input in the problem’s format." /></label>
              {user ? <button type="button" className="btn secondary small" onClick={() => queue('run', true)} disabled={!canRun}>
                <Play size={15} />Run custom input</button> : <Link to="/login" state={{ from: location.pathname }} className="text-link">Log in to use custom input</Link>}
            </div>}
            {currentTab === 'output' && (execution.restoring ? <Loading text="Reconnecting to the judge…" />
              : <SubmissionResults detail={execution.result} custom={execution.custom} linkToSubmission />)}
            {openedCoach && <div hidden={currentTab !== 'coach'}><PracticeCoach key={user?.id ?? 'guest'}
              user={user} problemId={problem.id} language={language} code={code} /></div>}
          </div>
        </div>
      </section>
    </div>
  </>;
}
