import { BarChart3 } from 'lucide-react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { Empty } from './ui';

export function ActivityGrid({ activity }) {
  const counts = new Map(activity.map((day) => [day.date, day.count]));
  const current = new Date();
  const days = Array.from({ length: 91 }, (_, index) => {
    const date = new Date(Date.UTC(current.getUTCFullYear(), current.getUTCMonth(), current.getUTCDate() - 90 + index));
    const key = date.toISOString().slice(0, 10);
    return { key, count: counts.get(key) || 0 };
  });
  return <div className="activity-grid-wrap">
    <div className="activity-grid" role="img" aria-label={'Submission activity over 91 days. ' + days.reduce((total, day) => total + day.count, 0) + ' submissions.'}>
      {days.map(({ key, count }) => <span aria-hidden="true" key={key} title={key + ' · ' + count + ' submissions'}
        className={'heat heat-' + Math.min(count, 4)} />)}
    </div>
    <div className="heat-legend"><span>Less</span>{[0, 1, 2, 3, 4].map((level) => <i key={level} className={'heat heat-' + level} />)}<span>More</span></div>
  </div>;
}

export function TrendChart({ data, label, chartId, maximum }) {
  if (!data.length) return <Empty icon={BarChart3} title="Activity will appear here after submissions" />;
  return <div className="chart" role="img" aria-label={label}>
    <ResponsiveContainer width="100%" height={220} minWidth={0}>
      <AreaChart data={data} margin={{ top: 10, right: 12, left: -16, bottom: 0 }}>
        <defs><linearGradient id={chartId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#9c8dff" stopOpacity={0.45} /><stop offset="100%" stopColor="#9c8dff" stopOpacity={0.03} />
        </linearGradient></defs>
        <CartesianGrid stroke="var(--line)" vertical={false} />
        <XAxis dataKey="date" tickFormatter={(date) => date.slice(5)} tick={{ fill: 'var(--muted)', fontSize: 11 }} tickLine={false} />
        <YAxis domain={maximum ? [0, maximum] : [0, 'auto']} allowDecimals={false}
          tick={{ fill: 'var(--muted)', fontSize: 11 }} tickLine={false} />
        <Tooltip contentStyle={{ background: 'var(--panel)', borderColor: 'var(--line)', color: 'var(--ink)', borderRadius: 8 }} />
        <Area type="monotone" dataKey="count" name={label} stroke="#9586ff" strokeWidth={2} dot={{ r: 3 }}
          fill={'url(#' + chartId + ')'} isAnimationActive={false} />
      </AreaChart>
    </ResponsiveContainer>
  </div>;
}

export function Breakdown({ items }) {
  const total = items.reduce((sum, item) => sum + item.count, 0);
  return <div className="breakdown">{items.map(({ label, count }) => <div key={label}>
    <span>{label}</span><div className="breakdown-track"><i style={{ width: (total ? 100 * count / total : 0) + '%' }} /></div><strong>{count}</strong>
  </div>)}</div>;
}
