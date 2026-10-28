import { Link } from 'react-router-dom'
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
import {
  categoryColor,
  formatNumber,
  formatTimestamp,
  relativeTime,
  SEVERITY_ORDER,
} from '../lib/format'
import { useApi } from '../lib/useApi'
import type { DashboardSummary, Severity } from '../lib/types'
import {
  EmptyState,
  ErrorState,
  NoticeBanner,
  OriginBadge,
  PageHeader,
  PageLoader,
  Panel,
  SeverityBadge,
  StatCard,
  StatusBadge,
} from '../components/ui'
import { IconBell, IconChip, IconGauge, IconShield, IconAlert } from '../components/icons'

const SEVERITY_FILL: Record<Severity, string> = {
  critical: '#f43f5e',
  high: '#fb923c',
  medium: '#fbbf24',
  low: '#38bdf8',
  info: '#22d3ee',
}

const tooltipStyle = {
  background: '#0a1122',
  border: '1px solid #1e2f4f',
  borderRadius: 10,
  fontSize: 12,
  color: '#e6edf9',
}

export default function Dashboard() {
  const { data, error, loading, refetch } = useApi<DashboardSummary>('/api/dashboard/summary')

  if (loading && !data) return <PageLoader />
  if (error && !data) return <ErrorState error={error} onRetry={refetch} />
  if (!data) return <ErrorState error="No data returned." onRetry={refetch} />

  const k = data.kpis
  const predicted = Object.entries(data.predicted_distribution).map(([key, v]) => ({
    key,
    name: v.label,
    value: v.count,
  }))
  const severityData = SEVERITY_ORDER.map((s) => ({
    name: s,
    value: data.severity_counts?.[s] ?? 0,
    fill: SEVERITY_FILL[s],
  }))
  const hourly = data.hourly_traffic.map((value, hour) => ({
    name: `${String(hour).padStart(2, '0')}:00`,
    value,
  }))
  const maxHour = Math.max(...data.hourly_traffic, 1)

  return (
    <div className="space-y-5">
      <PageHeader
        breadcrumb="Security Operations"
        title="Dashboard"
        description="Live view of the traffic records, model predictions and alerts stored in this SentinelAI instance."
        actions={
          <Link to="/detection" className="btn btn-primary">
            Run detection
          </Link>
        }
      />

      <NoticeBanner
        tone="info"
        title="Demo data — no live packet capture"
        action={
          <Link to="/reports" className="btn btn-ghost">
            Data sources
          </Link>
        }
      >
        {data.data_sources.explanation}
      </NoticeBanner>

      {/* data-source chips */}
      <div className="grid gap-3 sm:grid-cols-3">
        {data.data_sources.sources.map((src) => (
          <div
            key={src.origin}
            className="panel flex items-start justify-between gap-3 px-4 py-3"
          >
            <div>
              <div className="flex items-center gap-2">
                <OriginBadge origin={src.origin} />
                <span className="text-[13px] font-medium text-slate-200">{src.name}</span>
              </div>
              <p className="mt-1.5 text-[11px] leading-relaxed text-mute-400">{src.description}</p>
            </div>
            <div className="text-right">
              <div className="text-lg font-semibold tabular-nums text-white">
                {formatNumber(src.count)}
              </div>
              <div className="label-mono">rows</div>
            </div>
          </div>
        ))}
      </div>

      {/* KPIs */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="Traffic records"
          value={formatNumber(k.traffic_records)}
          hint={`${formatNumber(k.scored_records)} scored by the model`}
          icon={<IconGauge size={17} />}
          accent="sky"
          live
        />
        <StatCard
          label="Threats detected"
          value={formatNumber(k.threats_detected)}
          hint="Predictions labelled as an attack"
          icon={<IconChip size={17} />}
          accent="rose"
        />
        <StatCard
          label="Open alerts"
          value={formatNumber(k.alerts_open)}
          hint={`${formatNumber(k.alerts_total)} total · ${formatNumber(k.alerts_resolved)} resolved`}
          icon={<IconBell size={17} />}
          accent="amber"
        />
        <StatCard
          label="Critical severity"
          value={formatNumber(k.critical_alerts)}
          hint={`${formatNumber(k.alerts_model_generated)} model · ${formatNumber(k.alerts_simulated)} simulated`}
          icon={<IconShield size={17} />}
          accent="emerald"
        />
      </div>

      {/* charts */}
      <div className="grid gap-4 xl:grid-cols-3">
        <Panel
          title="Attack distribution"
          subtitle="Model predictions across all scored records"
          actions={<Link to="/detection" className="text-[11px] text-cyan-300 hover:underline">Details</Link>}
        >
          {predicted.length === 0 ? (
            <EmptyState
              icon="◔"
              title="No predictions yet"
              description="Score a CSV on the AI Detection page and the predicted class distribution will appear here."
              action={<Link to="/detection" className="btn btn-primary">Upload traffic CSV</Link>}
            />
          ) : (
            <div className="h-[248px]">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={predicted}
                    dataKey="value"
                    nameKey="name"
                    innerRadius={58}
                    outerRadius={92}
                    paddingAngle={1.5}
                    stroke="#070c1a"
                  >
                    {predicted.map((entry) => (
                      <Cell key={entry.key} fill={categoryColor(entry.key)} />
                    ))}
                  </Pie>
                  <Tooltip contentStyle={tooltipStyle} />
                </PieChart>
              </ResponsiveContainer>
            </div>
          )}
          <ul className="mt-3 grid grid-cols-2 gap-x-3 gap-y-1.5 text-[11px]">
            {predicted.slice(0, 8).map((p) => (
              <li key={p.key} className="flex items-center justify-between gap-2">
                <span className="flex items-center gap-1.5 truncate text-mute-300">
                  <span
                    className="h-2 w-2 shrink-0 rounded-sm"
                    style={{ background: categoryColor(p.key) }}
                  />
                  {p.name}
                </span>
                <span className="tabular-nums text-mute-200">{formatNumber(p.value)}</span>
              </li>
            ))}
          </ul>
        </Panel>

        <Panel
          title="Alert severity breakdown"
          subtitle="All alerts currently stored"
          actions={<Link to="/alerts" className="text-[11px] text-cyan-300 hover:underline">Console</Link>}
        >
          <div className="h-[248px]">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={severityData} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                <CartesianGrid stroke="#16233d" strokeDasharray="3 3" vertical={false} />
                <XAxis
                  dataKey="name"
                  tick={{ fill: '#7b8db0', fontSize: 11 }}
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
                  formatter={(value) => [formatNumber(Number(value)), 'Alerts']}
                />
                <Bar dataKey="value" radius={[6, 6, 0, 0]}>
                  {severityData.map((entry) => (
                    <Cell key={entry.name} fill={entry.fill} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-2 text-[11px]">
            {SEVERITY_ORDER.map((s) => (
              <div key={s} className="flex items-center justify-between">
                <span className="flex items-center gap-1.5 text-mute-400">
                  <span
                    className="h-2 w-2 rounded-full"
                    style={{ background: SEVERITY_FILL[s] }}
                  />
                  {s}
                </span>
                <span className="tabular-nums text-mute-200">
                  {formatNumber(data.severity_counts?.[s] ?? 0)}
                </span>
              </div>
            ))}
          </div>
        </Panel>

        <Panel
          title="Traffic by hour of day"
          subtitle="UTC hour · peaks mark the recorded attack windows"
        >
          <div className="h-[248px]">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={hourly} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
                <CartesianGrid stroke="#16233d" strokeDasharray="3 3" vertical={false} />
                <XAxis
                  dataKey="name"
                  interval={3}
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
                <Bar dataKey="value" fill="#0891b2" radius={[3, 3, 0, 0]} maxBarSize={18} />
              </BarChart>
            </ResponsiveContainer>
          </div>
          <p className="mt-3 text-[11px] leading-relaxed text-mute-400">
            Busiest hour: <span className="text-cyan-300 tabular-nums">{formatNumber(maxHour)}</span>{' '}
            records. Hours are taken from the original 2017 capture timestamps for dataset rows and
            from generation time for simulated rows.
          </p>
        </Panel>
      </div>

      {/* recent alerts + side panel */}
      <div className="grid gap-4 xl:grid-cols-3">
        <Panel
          className="xl:col-span-2"
          title="Recent alerts"
          subtitle="Newest first · mixed model detections and simulated demo alerts"
          actions={
            <Link to="/alerts" className="btn btn-ghost">
              Open alert console
            </Link>
          }
          bodyClassName="px-0 py-0"
        >
          {data.recent_alerts.length === 0 ? (
            <div className="px-5 py-6">
              <EmptyState
                icon="✓"
                title="No alerts yet"
                description="Alerts appear once the model scores traffic records or once demo data is seeded."
              />
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-[12.5px]">
                <thead>
                  <tr className="label-mono border-b border-ink-600/70">
                    <th className="px-5 py-2.5 font-normal">Severity</th>
                    <th className="px-3 py-2.5 font-normal">Alert</th>
                    <th className="px-3 py-2.5 font-normal">Source</th>
                    <th className="px-3 py-2.5 font-normal">Status</th>
                    <th className="px-5 py-2.5 text-right font-normal">When</th>
                  </tr>
                </thead>
                <tbody>
                  {data.recent_alerts.map((a) => (
                    <tr key={a.id} className="table-row-hover border-b border-ink-700/60">
                      <td className="px-5 py-2.5">
                        <SeverityBadge severity={a.severity} />
                      </td>
                      <td className="px-3 py-2.5">
                        <Link
                          to={`/alerts?id=${a.id}`}
                          className="block font-medium text-slate-200 hover:text-cyan-300"
                        >
                          {a.title}
                        </Link>
                        <span className="text-[11px] text-mute-400">{a.category_label}</span>
                      </td>
                      <td className="px-3 py-2.5 font-mono text-[11.5px] text-mute-300">
                        {a.source_ip ?? '—'}
                        <span className="text-mute-400/70"> → </span>
                        {a.destination_ip ?? '—'}
                      </td>
                      <td className="px-3 py-2.5">
                        <div className="flex flex-wrap gap-1">
                          <StatusBadge status={a.status} />
                          <OriginBadge origin={a.origin} />
                        </div>
                      </td>
                      <td className="px-5 py-2.5 text-right text-[11.5px] text-mute-400">
                        <span title={formatTimestamp(a.occurred_at ?? a.created_at)}>
                          {relativeTime(a.occurred_at ?? a.created_at)}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Panel>

        <div className="space-y-4">
          <Panel title="Model status">
            {data.model.available ? (
              <dl className="space-y-2.5 text-[12px]">
                <div className="flex items-center justify-between gap-3">
                  <dt className="text-mute-400">State</dt>
                  <dd className="flex items-center gap-1.5 text-emerald-300">
                    <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
                    Trained &amp; serving
                  </dd>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <dt className="text-mute-400">Version</dt>
                  <dd className="font-mono text-[11px] text-slate-200">
                    {data.model.model_version ?? '—'}
                  </dd>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <dt className="text-mute-400">Trained</dt>
                  <dd className="text-slate-200">{formatTimestamp(data.model.trained_at)}</dd>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <dt className="text-mute-400">Eval report</dt>
                  <dd className={data.model.metrics_loaded ? 'text-emerald-300' : 'text-amber-300'}>
                    {data.model.metrics_loaded ? 'available' : 'missing'}
                  </dd>
                </div>
              </dl>
            ) : (
              <div className="space-y-3">
                <p className="text-xs text-amber-300">The model has not been trained yet.</p>
                <p className="text-[11px] leading-relaxed text-mute-400">
                  Run <code className="text-cyan-300">python -m app.ml.dataset</code> then{' '}
                  <code className="text-cyan-300">python -m app.ml.train</code> inside{' '}
                  <code>backend/</code>.
                </p>
              </div>
            )}
          </Panel>

          <Panel title="Top targeted ports" subtitle="Across all stored traffic records">
            {data.top_target_ports.length === 0 ? (
              <p className="text-[12px] text-mute-400">No port data available yet.</p>
            ) : (
              <ul className="space-y-2 text-[12px]">
                {data.top_target_ports.map((p) => (
                  <li key={p.port} className="flex items-center justify-between gap-3">
                    <span className="font-mono text-slate-300">:{p.port}</span>
                    <span className="flex flex-1 items-center gap-2">
                      <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-ink-700">
                        <span
                          className="block h-full rounded-full bg-cyan-400/70"
                          style={{
                            width: `${Math.min(100, (p.n / (data.top_target_ports[0]?.n || 1)) * 100)}%`,
                          }}
                        />
                      </span>
                    </span>
                    <span className="w-24 text-right tabular-nums text-mute-400">
                      {formatNumber(p.n)}
                      {p.attacks > 0 ? (
                        <span className="ml-1.5 text-rose-300">
                          ({formatNumber(p.attacks)} attack)
                        </span>
                      ) : null}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </Panel>

          <Panel title="Protocol mix">
            <div className="flex flex-wrap gap-2">
              {data.protocol_counts.map((p) => (
                <span
                  key={p.protocol}
                  className="rounded-lg border border-ink-600 bg-ink-850 px-3 py-2 text-[12px]"
                >
                  <span className="text-mute-400">{p.protocol}</span>{' '}
                  <span className="ml-1 font-semibold text-slate-200 tabular-nums">
                    {formatNumber(p.n)}
                  </span>
                </span>
              ))}
              {data.protocol_counts.length === 0 ? (
                <span className="text-[12px] text-mute-400">No protocol data.</span>
              ) : null}
            </div>
          </Panel>

          <div className="panel flex items-start gap-3 px-4 py-3">
            <IconAlert size={16} className="mt-0.5 shrink-0 text-amber-300" />
            <p className="text-[11px] leading-relaxed text-mute-400">
              Alerts tagged <span className="text-violet-300">Simulated</span> come from the sample
              data seeder. Alerts tagged <span className="text-cyan-300">Model</span> were produced
              by the trained classifier running on real CIC-IDS2017 records or uploaded CSVs.
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}
