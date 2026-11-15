import { ArrowUpRight, Clock3, Cpu, LoaderCircle, Terminal } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Badge, Empty, LANGUAGE_NAMES } from './ui';

export function isPending(submission) {
  return submission?.verdict === 'PENDING' || submission?.verdict === 'RUNNING';
}

export default function SubmissionResults({ detail, custom = false, linkToSubmission = false }) {
  if (!detail) return <Empty icon={Terminal} title="Run a test to see its output">
    Use the provided examples or enter your own input.
  </Empty>;
  const { submission, diagnostic, cases } = detail;
  const pending = isPending(submission);
  const firstFailed = cases.findIndex((item) => item.verdict !== 'ACCEPTED');
  const submitted = submission.mode === 'SUBMIT';
  return <div className="submission-results">
    <div className="result-header" role="status">
      <Badge value={submission.verdict} />
      <span>{LANGUAGE_NAMES[submission.language]}</span>
      {submission.passedCases != null && !custom && <strong>{submission.passedCases} / {submission.totalCases} cases passed</strong>}
      {submission.elapsedMs != null && <span><Clock3 size={14} />{submission.elapsedMs} ms</span>}
      {submission.memoryKb != null && <span><Cpu size={14} />{(submission.memoryKb / 1024).toFixed(1)} MB</span>}
    </div>
    {pending && <p className="result-pending small-text"><LoaderCircle className="spin" size={15} />
      {submission.verdict === 'PENDING' ? 'Waiting for an available judge.' : 'Running your code…'}</p>}
    {diagnostic && <pre className="output-block diagnostic" role="alert">{diagnostic}</pre>}
    {cases.map((item, index) => <details className="test-result" key={submission.id + ':' + item.position}
      open={index === Math.max(firstFailed, 0) || undefined}>
      <summary><span>{custom ? 'Custom input' : 'Sample ' + (index + 1)}</span><Badge value={item.verdict} /></summary>
      <div className={'test-output-grid' + (item.expectedOutput != null ? ' comparison' : '')}>
        {item.expectedOutput != null && <div className="test-output">
          <span>Expected output</span><pre className="output-block">{item.expectedOutput || '(empty)'}</pre>
        </div>}
        <div className={'test-output' + (item.verdict === 'WRONG_ANSWER' ? ' mismatch' : '')}>
          <span>{item.expectedOutput == null ? 'Standard output' : 'Your output'}</span>
          <pre className="output-block">{item.stdout || '(empty)'}</pre>
        </div>
      </div>
      {item.stderr && <div className="test-output test-stderr">
        <span>Standard error</span><pre className="output-block diagnostic">{item.stderr}</pre>
      </div>}
    </details>)}
    {!pending && submitted && <div className="result-footer">
      <p className="small-text">The case count includes hidden tests.</p>
      {linkToSubmission && <Link className="text-link" to={'/submissions/' + submission.id}>
        View submission <ArrowUpRight size={14} />
      </Link>}
    </div>}
  </div>;
}
