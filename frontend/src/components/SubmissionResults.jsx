import { Clock3, Cpu, Terminal } from 'lucide-react';
import { Badge, Empty, LANGUAGE_NAMES } from './ui';

export function isPending(submission) {
  return submission?.verdict === 'PENDING' || submission?.verdict === 'RUNNING';
}

export default function SubmissionResults({ detail, custom = false }) {
  if (!detail) return <Empty icon={Terminal} title="Run a test to see its output">
    Use the provided examples or enter your own input.
  </Empty>;
  const { submission, diagnostic, cases } = detail;
  const pending = isPending(submission);
  return <div className="submission-results" aria-live="polite">
    <div className="result-header">
      <Badge value={submission.verdict} />
      <span>{LANGUAGE_NAMES[submission.language]}</span>
      {submission.passedCases != null && !custom && <span>{submission.passedCases} / {submission.totalCases} cases passed</span>}
      {submission.elapsedMs != null && <span><Clock3 size={14} />{submission.elapsedMs} ms</span>}
      {submission.memoryKb != null && <span><Cpu size={14} />{(submission.memoryKb / 1024).toFixed(1)} MB</span>}
    </div>
    {pending && <p className="small-text">The judge will update this result as soon as it finishes.</p>}
    {diagnostic && <pre className="output-block diagnostic">{diagnostic}</pre>}
    {cases.map((item, index) => <details className="test-result" key={item.position} open={cases.length === 1 || index === 0 || undefined}>
      <summary><span>{custom ? 'Custom input' : 'Sample ' + (index + 1)}</span><Badge value={item.verdict} /></summary>
      <div className="test-output"><span>Standard output</span><pre className="output-block">{item.stdout || '(empty)'}</pre></div>
      {item.stderr && <div className="test-output"><span>Standard error</span><pre className="output-block diagnostic">{item.stderr}</pre></div>}
    </details>)}
    {!pending && submission.mode === 'SUBMIT' && <p className="small-text">The case count includes hidden tests. Output is shown for sample tests.</p>}
  </div>;
}
