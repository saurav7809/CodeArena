import { AlertCircle, ArrowLeft, ArrowRight, ChevronLeft, ChevronRight, LoaderCircle } from 'lucide-react';
import { Link } from 'react-router-dom';

export const LANGUAGE_NAMES = { JAVA: 'Java 17', PYTHON: 'Python 3', CPP: 'C++17' };
export const VERDICTS = {
  PENDING: 'Queued', RUNNING: 'Running', ACCEPTED: 'Accepted', WRONG_ANSWER: 'Wrong answer',
  COMPILATION_ERROR: 'Compilation error', RUNTIME_ERROR: 'Runtime error', TIME_LIMIT_EXCEEDED: 'Time limit exceeded',
  MEMORY_LIMIT_EXCEEDED: 'Memory limit exceeded', INTERNAL_ERROR: 'Judge unavailable',
};
export const CATEGORIES = ['Arrays', 'Strings', 'Linked Lists', 'Trees', 'Graphs', 'Dynamic Programming', 'Greedy',
  'Sorting', 'Searching', 'Recursion', 'Backtracking', 'Mathematics', 'SQL'];

export function dateTime(value) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

export function number(value) { return new Intl.NumberFormat().format(value); }

export function Badge({ value, kind }) {
  const text = VERDICTS[value] || value.replaceAll('_', ' ').toLowerCase();
  const color = kind || ({
    EASY: 'green', MEDIUM: 'amber', HARD: 'red', ACCEPTED: 'green', PENDING: 'amber', RUNNING: 'violet',
    ONGOING: 'green', UPCOMING: 'violet', PUBLISHED: 'green', PAST: 'muted', DRAFT: 'muted', CANCELLED: 'red',
  })[value] || (Object.hasOwn(VERDICTS, value) ? 'red' : 'muted');
  return <span className={'badge ' + color}><span className="status-dot" />{text}</span>;
}

export function PageTitle({ eyebrow, title, description, action }) {
  return <div className="page-title">
    <div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1>{title}</h1>{description && <p>{description}</p>}</div>
    {action}
  </div>;
}

export function SectionTitle({ title, description, to, linkLabel = 'View all' }) {
  return <div className="section-title"><div><h2>{title}</h2>{description && <p>{description}</p>}</div>
    {to && <Link className="text-link" to={to}>{linkLabel}<ArrowRight size={15} /></Link>}</div>;
}

export function Loading({ text = 'Loading…' }) {
  return <div className="loading" role="status"><LoaderCircle size={19} className="spin" />{text}</div>;
}

export function ErrorNotice({ text, retry }) {
  return <div className="notice error" role="alert"><AlertCircle size={18} /><span>{text}</span>
    {retry && <button type="button" className="btn small secondary" onClick={retry}>Retry</button>}</div>;
}

export function Empty({ icon: Icon, title, children }) {
  return <div className="empty">
    {Icon && <div className="empty-icon"><Icon size={21} /></div>}
    <strong>{title}</strong>{children && <div>{children}</div>}
  </div>;
}

export function Resource({ state, children }) {
  if (state.loading) return <Loading />;
  if (state.error) return <ErrorNotice text={state.error} retry={state.refresh} />;
  return state.data == null ? null : children(state.data);
}

export function Pagination({ data, onChange }) {
  if (data.total <= data.size && data.page === 0) return null;
  const pages = Math.max(1, Math.ceil(data.total / data.size));
  return <div className="pagination">
    <span>Page {data.page + 1} of {pages}</span>
    <div className="button-row">
      <button className="icon-button" type="button" aria-label="Previous page" disabled={data.page === 0}
        onClick={() => onChange(data.page - 1)}><ChevronLeft size={18} /></button>
      <button className="icon-button" type="button" aria-label="Next page" disabled={data.page + 1 >= pages}
        onClick={() => onChange(data.page + 1)}><ChevronRight size={18} /></button>
    </div>
  </div>;
}

export function Metric({ label, value, icon: Icon, detail }) {
  return <div className="metric">
    <div className="metric-top"><span>{label}</span>{Icon && <Icon size={17} />}</div>
    <strong>{value}</strong>
    {detail && <span className="small-text">{detail}</span>}
  </div>;
}

export function BackLink({ to, children }) {
  return <Link className="back-link" to={to}><ArrowLeft size={15} />{children}</Link>;
}

export function Avatar({ username, size = 'normal' }) {
  return <span className={'avatar ' + size} aria-hidden="true">{username.slice(0, 2).toUpperCase()}</span>;
}
