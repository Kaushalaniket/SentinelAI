import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { api } from '../lib/api'
import { categoryColor, formatBytes, formatNumber, formatPercent, formatTimestamp } from '../lib/format'
import { useApi } from '../lib/useApi'
import type { ReportSummary } from '../lib/types'
import {
  EmptyState,
  ErrorState,
  NoticeBanner,
  PageHeader,
  PageLoader,
  Panel,
  StatCard,
} from '../components/ui'
import { IconDownload, IconReport } from '../components/icons'

const tooltipStyle = {
  background: '#0a1122',
  border: '1px solid #1e2f4f',
  borderRadius: 10,
  fontSize: 12,
  color: '#e6edf9',
}

const EXPORTS: { kind: string; label: string; description: string }[] = [
  {
    kind: 'traffic',
    label: 'Traffic records',
    description: 'All stored flows with origin, endpoints, volume and ground-truth label.',
  },
  {
    kind: 'alerts',
    label: 'Alerts',
    description: 'Severity, status, provenance, timestamps and full incident description.',
  },
  {
    kind: 'predictions',
    label: 'Model predictions',
    description: 'Per-record prediction, confidence, model version and ground truth.',
  },
  {
    kind: 'intel',
    label: 'Threat-intel lookups',
    description: 'Every IP reputation query with its source, verdict and cache mode.',
  },
]

export default function Reports() {
  const { data, error, loading, refetch } = useApi<ReportSummary>('/api/reports/summary')

  if (loading && !data) return <PageLoader />
  if (error && !data) return <ErrorState error={error} onRetry={refetch} />
  if (!data) return <ErrorState error="No report data." onRetry={refetch} />

  const categoryRows = Object.entries(data.traffic.by_category).map(([key, v]) => ({
    key,
    name: v.label,
    value: v.count,
  }))
  const originRows = Object.entries(data.traffic.by_origin).map(([origin, count]) => ({
    name:
      origin === 'dataset'
        ? 'CIC-IDS2017'
        : origin === 'simulated'
          ? 'Simulated'
          : origin === 'upload'
            ? 'Uploaded'
            : origin,
    value: count,
    origin,
  }))
  const originFill: Record<string, string> = {
    dataset: '#14b8a6',
    simulated: '#8b5cf6',
    upload: '#6366f1',
  }
  const severityRows = Object.entries(data.alerts.by_severity)
  const severityFill: Record<string, string> = {
    critical: '#f43f5e',
    high: '#fb923c',
    medium: '#fbbf24',
    low: '#38bdf8',
    info: '#22d3ee',
  }

  return (
    <div className="space-y-5">
      <PageHeader
        breadcrumb="Reporting"
        title="Reports"
        description="Summary statistics for the whole system plus CSV exports you can hand in with your project report."
        actions={
          <>
            <button type="button" className="btn btn-ghost" onClick={refetch}>
              Recompute
            </button>
            <a className="btn btn-primary" href={api.csv('alerts')}>
              <IconDownload size={14} />
              Export alerts CSV
            </a>
          </>
        }
      />

      <NoticeBanner tone="warning" title="What these numbers describe">
        {data.data_caveat} Generated at {formatTimestamp(data.generated_at)}.
      </NoticeBanner>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Traffic records"
          value={formatNumber(data.traffic.total)}
          hint={formatBytes(data.traffic.volume.total_bytes) + ' total volume'}
        />
        <StatCard
          label="Alerts"
          value={formatNumber(data.alerts.total)}
          accent="amber"
          hint={`${formatNumber(data.alerts.by_severity.critical)} critical · ${formatNumber(data.alerts.by_severity.high)} high`}
        />
        <StatCard
          label="Model predictions"
          value={formatNumber(data.predictions.total)}
          accent="rose"
          hint={`${formatNumber(data.predictions.attacks)} attacks · ${formatNumber(data.predictions.benign)} benign`}
        />
        <StatCard
          label="Model accuracy"
          value={data.model.available ? formatPercent(data.model.accuracy, 2) : '—'}
          accent="emerald"
          hint={
            data.model.available
              ? `macro-F1 ${formatPercent(data.model.f1_macro, 2)}`
              : 'model not trained'
          }
        />
      </div>

      <div className="grid gap-4 xl:grid-cols-3">
        <Panel title="Traffic records by category" subtitle="Ground-truth labels where available">
          <div className="h-[260px]">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={categoryRows} margin={{ top: 8, right: 8, left: -14, bottom: 40 }}>
                <CartesianGrid stroke="#16233d" strokeDasharray="3 3" vertical={false} />
                <XAxis
                  dataKey="name"
                  angle={-35}
                  textAnchor="end"
                  interval={0}
                  height={60}
                  tick={{ fill: '#7b8db0', fontSize: 10 }}
                  axisLine={{ stroke: '#1e2f4f' }}
                  tickLine={false}
                />
                <YAxis
                  allowDecimals={false}
                  tick={{ fill: '#7b8db0', fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip
                  cursor={{ fill: 'rgba(34,211,238,0.06)' }}
                  contentStyle={tooltipStyle}
                  formatter={(value) => [formatNumber(Number(value)), 'Records']}
                />
                <Bar dataKey="value" radius={[5, 5, 0, 0]}>
                  {categoryRows.map((c) => (
                    <Cell key={c.key} fill={categoryColor(c.key)} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Panel>

        <Panel title="Records by origin" subtitle="Dataset replay vs simulated vs uploaded">
          <div className="h-[260px]">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={originRows}
                  dataKey="value"
                  nameKey="name"
                  innerRadius={54}
                  outerRadius={92}
                  paddingAngle={2}
                  stroke="#070c1a"
                >
                  {originRows.map((o) => (
                    <Cell key={o.origin} fill={originFill[o.origin] ?? '#64748b'} />
                  ))}
                </Pie>
                <Tooltip contentStyle={tooltipStyle} formatter={(v, n) => [formatNumber(Number(v)), String(n)]} />
              </PieChart>
            </ResponsiveContainer>
          </div>
          <ul className="mt-2 space-y-1 text-[11.5px]">
            {originRows.map((o) => (
              <li key={o.origin} className="flex items-center justify-between">
                <span className="flex items-center gap-2 text-mute-300">
                  <span
                    className="h-2 w-2 rounded-sm"
                    style={{ background: originFill[o.origin] ?? '#64748b' }}
                  />
                  {o.name}
                </span>
                <span className="tabular-nums text-slate-200">{formatNumber(o.value)}</span>
              </li>
            ))}
          </ul>
        </Panel>

        <Panel title="Alerts by severity" subtitle="All origins">
          <div className="h-[260px]">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={severityRows.map(([name, value]) => ({ name, value }))}
                  dataKey="value"
                  nameKey="name"
                  outerRadius={96}
                  paddingAngle={1.5}
                  stroke="#070c1a"
                >
                  {severityRows.map(([name]) => (
                    <Cell key={name} fill={severityFill[name] ?? '#64748b'} />
                  ))}
                </Pie>
                <Tooltip contentStyle={tooltipStyle} formatter={(v, n) => [formatNumber(Number(v)), String(n)]} />
              </PieChart>
            </ResponsiveContainer>
          </div>
          <ul className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-[11.5px]">
            {severityRows.map(([name, value]) => (
              <li key={name} className="flex items-center justify-between">
                <span className="flex items-center gap-2 text-mute-300">
                  <span
                    className="h-2 w-2 rounded-full"
                    style={{ background: severityFill[name] ?? '#64748b' }}
                  />
                  {name}
                </span>
                <span className="tabular-nums text-slate-200">{formatNumber(value)}</span>
              </li>
            ))}
          </ul>
        </Panel>
      </div>

      <div className="grid gap-4 xl:grid-cols-3">
        <Panel
          className="xl:col-span-2"
          title="CSV export"
          subtitle="Straight from SQLite — no post-processing, so what you export is what the app stored."
        >
          <div className="grid gap-3 sm:grid-cols-2">
            {EXPORTS.map((e) => (
              <div
                key={e.kind}
                className="flex items-start justify-between gap-3 rounded-xl border border-ink-600 bg-ink-850/60 px-4 py-3"
              >
                <div className="min-w-0">
                  <div className="text-[13px] font-medium text-slate-200">{e.label}</div>
                  <p className="mt-1 text-[11px] leading-relaxed text-mute-400">{e.description}</p>
                </div>
                <a className="btn btn-ghost shrink-0" href={api.csv(e.kind)} download>
                  <IconDownload size={13} />
                  CSV
                </a>
              </div>
            ))}
          </div>

          <div className="mt-4 rounded-xl border border-ink-600 bg-ink-900/70 px-4 py-3">
            <div className="label-mono">Optional filters</div>
            <p className="mt-1 text-[11.5px] leading-relaxed text-mute-400">
              Add <code className="text-cyan-300">?kind=traffic&amp;origin=dataset</code> to export
              only real CIC-IDS2017 rows, or <code className="text-cyan-300">&amp;limit=1000</code>{' '}
              to cap the number of rows. Example links:
            </p>
            <div className="mt-2 flex flex-wrap gap-2">
              <a className="btn btn-ghost" href={api.csv('traffic', 'dataset')} download>
                CIC-IDS2017 rows only
              </a>
              <a className="btn btn-ghost" href={api.csv('traffic', 'simulated')} download>
                Simulated rows only
              </a>
              <a className="btn btn-ghost" href={api.csv('traffic', 'upload')} download>
                Uploaded rows only
              </a>
            </div>
          </div>
        </Panel>

        <Panel title="Model evaluation summary">
          {data.model.available ? (
            <dl className="space-y-2 text-[12px]">
              <Line label="Accuracy" value={formatPercent(data.model.accuracy, 2)} />
              <Line label="Precision (macro)" value={formatPercent(data.model.precision_macro, 2)} />
              <Line label="Recall (macro)" value={formatPercent(data.model.recall_macro, 2)} />
              <Line label="F1 (macro)" value={formatPercent(data.model.f1_macro, 2)} />
              <Line label="F1 (weighted)" value={formatPercent(data.model.f1_weighted, 2)} />
              <Line label="Test rows" value={formatNumber(data.model.test_rows)} />
              <Line label="Trained" value={formatTimestamp(data.model.trained_at)} />
            </dl>
          ) : (
            <p className="text-[12px] text-amber-300">Model not trained — run app.ml.train.</p>
          )}
          <div className="mt-4 border-t border-ink-700/60 pt-3">
            <div className="label-mono mb-2">Baselines</div>
            <ul className="space-y-1.5 text-[11.5px]">
              {Object.entries(data.model.baselines ?? {}).map(([name, b]) => (
                <li key={name} className="flex items-center justify-between gap-2">
                  <span className="text-mute-400">{name.replace(/_/g, ' ')}</span>
                  <span className="tabular-nums text-slate-200">
                    {formatPercent(b.accuracy, 2)}
                  </span>
                </li>
              ))}
              {Object.keys(data.model.baselines ?? {}).length === 0 ? (
                <li className="text-mute-400">No baseline recorded.</li>
              ) : null}
            </ul>
          </div>
        </Panel>
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Panel title="Top source addresses" subtitle="By predicted/labelled attack count">
          {data.top_sources.length === 0 ? (
            <EmptyState
              icon={<IconReport size={18} />}
              title="No IP-level data"
              description="Simulated and uploaded rows carry IP addresses; import or generate traffic to populate this table."
            />
          ) : (
            <table className="w-full text-left text-[12px]">
              <thead>
                <tr className="label-mono border-b border-ink-600/70">
                  <th className="py-2.5 pr-3 font-normal">Source IP</th>
                  <th className="px-3 py-2.5 text-right font-normal">Flows</th>
                  <th className="py-2.5 pl-3 text-right font-normal">Attacks</th>
                </tr>
              </thead>
              <tbody>
                {data.top_sources.map((s) => (
                  <tr key={s.ip} className="border-b border-ink-700/50">
                    <td className="py-2 pr-3 font-mono text-slate-200">{s.ip}</td>
                    <td className="px-3 py-2 text-right tabular-nums text-mute-300">
                      {formatNumber(s.flows)}
                    </td>
                    <td
                      className={`py-2 pl-3 text-right tabular-nums ${
                        s.attacks > 0 ? 'text-rose-300' : 'text-mute-400'
                      }`}
                    >
                      {formatNumber(s.attacks)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Panel>

        <Panel title="Dataset breakdown" subtitle="Volume and alert totals per source">
          <table className="w-full text-left text-[12px]">
            <thead>
              <tr className="label-mono border-b border-ink-600/70">
                <th className="py-2.5 pr-3 font-normal">Measure</th>
                <th className="px-3 py-2.5 text-right font-normal">Traffic</th>
                <th className="px-3 py-2.5 text-right font-normal">Alerts</th>
              </tr>
            </thead>
            <tbody>
              {['dataset', 'simulated', 'upload'].map((origin) => {
                const label =
                  origin === 'dataset'
                    ? 'CIC-IDS2017 replay'
                    : origin === 'simulated'
                      ? 'Simulated demo'
                      : 'Uploaded CSVs'
                return (
                  <tr key={origin} className="border-b border-ink-700/50">
                    <td className="py-2.5 pr-3 text-slate-200">{label}</td>
                    <td className="px-3 py-2.5 text-right tabular-nums text-mute-300">
                      {formatNumber(data.traffic.by_origin[origin] ?? 0)}
                    </td>
                    <td className="px-3 py-2.5 text-right tabular-nums text-mute-300">
                      {formatNumber(
                        origin === 'simulated'
                          ? (data.alerts.by_origin.simulated ?? 0)
                          : (data.alerts.by_origin.model ?? 0) *
                              (origin === 'dataset' ? 1 : 0),
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          <p className="mt-3 text-[11px] leading-relaxed text-mute-400">
            Model alerts are attributed to whichever record source the classifier scored
            (CIC-IDS2017 replay or an uploaded CSV). Simulated rows are intentionally never scored,
            so they can never generate a detection.
          </p>
        </Panel>
      </div>
    </div>
  )
}

function Line({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-ink-700/50 pb-1.5">
      <dt className="text-mute-400">{label}</dt>
      <dd className="tabular-nums text-slate-200">{value}</dd>
    </div>
  )
}
