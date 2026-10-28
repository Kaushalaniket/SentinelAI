import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, qs } from '../lib/api'
import { formatPercent, formatTimestamp, relativeTime, SEVERITY_ORDER } from '../lib/format'
import { useApi } from '../lib/useApi'
import type { Alert, AlertStatus, Severity } from '../lib/types'
import {
  Badge,
  Chip,
  EmptyState,
  ErrorState,
  NoticeBanner,
  OriginBadge,
  PageHeader,
  PageLoader,
  Panel,
  Pagination,
  SeverityBadge,
  StatusBadge,
} from '../components/ui'
import { IconBell, IconSearch } from '../components/icons'

interface AlertStats {
  total: number
  by_severity: Record<string, number>
  by_status: Record<string, number>
  by_origin: Record<string, number>
  by_category: Record<string, number>
  by_day: { day: string; total: number; severe: number }[]
}

interface AlertDetail {
  alert: Alert
  traffic_record: Record<string, unknown> | null
  prediction: {
    predicted_label: string
    confidence: number | null
    probabilities?: Record<string, number>
    model_version?: string
  } | null
  related_alerts: Alert[]
}

export default function Alerts() {
  const [params, setParams] = useSearchParams()
  const severity = params.get('severity') ?? ''
  const status = params.get('status') ?? ''
  const origin = params.get('origin') ?? ''
  const sort = params.get('sort') ?? 'newest'
  const q = params.get('q') ?? ''
  const page = Number(params.get('page') ?? 1)
  const [draft, setDraft] = useState(q)
  const [selected, setSelected] = useState<number | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const query = useMemo(
    () =>
      qs({
        severity: severity || undefined,
        status: status || undefined,
        origin: origin || undefined,
        sort,
        q: q || undefined,
        page,
        page_size: 25,
      }),
    [severity, status, origin, sort, q, page],
  )

  const list = useApi<{ items: Alert[]; page: number; pages: number; total: number }>(
    `/api/alerts${query}`,
  )
  const stats = useApi<AlertStats>('/api/alerts/stats')

  const update = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [key, value] of Object.entries(patch)) {
      if (!value) next.delete(key)
      else next.set(key, value)
    }
    if (!('page' in patch)) next.delete('page')
    setParams(next, { replace: true })
  }

  const changeStatus = async (id: number, next: AlertStatus) => {
    setActionError(null)
    try {
      await api.patch(`/api/alerts/${id}`, { status: next })
      list.refetch()
      stats.refetch()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : String(err))
    }
  }

  const items = list.data?.items ?? []
  const activeFilters = [severity, status, origin, q].filter(Boolean).length

  return (
    <div className="space-y-5">
      <PageHeader
        breadcrumb="Incident Management"
        title="Alerts"
        description="Every alert carries a severity, a timestamp, incident details and its origin — model detection or clearly-labelled simulated demo."
        actions={
          <button
            type="button"
            className="btn btn-ghost"
            disabled={(stats.data?.by_status?.open ?? 0) === 0}
            onClick={async () => {
              const open = items.filter((a) => a.status === 'open')
              for (const a of open) {
                await changeStatus(a.id, 'acknowledged')
              }
            }}
          >
            Acknowledge page
          </button>
        }
      />

      <NoticeBanner tone="info" title="Two kinds of alert live in this console">
        <span className="text-cyan-300">Model</span> alerts were produced by the trained classifier
        scoring real CIC-IDS2017 records or uploaded CSVs.{' '}
        <span className="text-violet-300">Simulated</span> alerts were written by the sample-data
        seeder purely so the console has history to show — they are not detections. Filter by
        Origin to see either group on its own.
      </NoticeBanner>

      {actionError ? <ErrorState error={actionError} onRetry={() => setActionError(null)} /> : null}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <SummaryTile label="Total alerts" value={stats.data?.total ?? 0} tone="cyan" />
        <SummaryTile label="Critical" value={stats.data?.by_severity?.critical ?? 0} tone="rose" />
        <SummaryTile label="High" value={stats.data?.by_severity?.high ?? 0} tone="amber" />
        <SummaryTile label="Open" value={stats.data?.by_status?.open ?? 0} tone="sky" />
        <SummaryTile label="Resolved" value={stats.data?.by_status?.resolved ?? 0} tone="emerald" />
      </div>

      <Panel
        title="Filters"
        subtitle={`${formatNumberSafe(list.data?.total)} match${activeFilters ? ` · ${activeFilters} filter(s) active` : ''}`}
        actions={
          <button
            type="button"
            className="btn btn-ghost"
            onClick={() => {
              setDraft('')
              setParams(new URLSearchParams(), { replace: true })
            }}
          >
            Reset
          </button>
        }
      >
        <div className="space-y-4">
          <div>
            <div className="label-mono mb-1.5">Severity</div>
            <div className="flex flex-wrap gap-1.5">
              <Chip active={!severity} onClick={() => update({ severity: null })}>
                All
              </Chip>
              {SEVERITY_ORDER.map((s) => (
                <Chip
                  key={s}
                  active={severity === s}
                  onClick={() => update({ severity: severity === s ? null : s })}
                  count={stats.data?.by_severity?.[s as Severity] ?? 0}
                >
                  {s}
                </Chip>
              ))}
            </div>
          </div>

          <div className="flex flex-wrap gap-6">
            <div>
              <div className="label-mono mb-1.5">Status</div>
              <div className="flex flex-wrap gap-1.5">
                <Chip active={!status} onClick={() => update({ status: null })}>
                  Any
                </Chip>
                {['open', 'acknowledged', 'resolved'].map((s) => (
                  <Chip
                    key={s}
                    active={status === s}
                    onClick={() => update({ status: status === s ? null : s })}
                    count={stats.data?.by_status?.[s] ?? 0}
                  >
                    {s}
                  </Chip>
                ))}
              </div>
            </div>

            <div>
              <div className="label-mono mb-1.5">Origin</div>
              <div className="flex flex-wrap gap-1.5">
                <Chip active={!origin} onClick={() => update({ origin: null })}>
                  Any
                </Chip>
                <Chip
                  active={origin === 'model'}
                  onClick={() => update({ origin: origin === 'model' ? null : 'model' })}
                  count={stats.data?.by_origin?.model ?? 0}
                >
                  Model
                </Chip>
                <Chip
                  active={origin === 'simulated'}
                  onClick={() => update({ origin: origin === 'simulated' ? null : 'simulated' })}
                  count={stats.data?.by_origin?.simulated ?? 0}
                >
                  Simulated
                </Chip>
              </div>
            </div>

            <div>
              <div className="label-mono mb-1.5">Sort</div>
              <select
                className="field"
                value={sort}
                onChange={(e) => update({ sort: e.target.value })}
              >
                <option value="newest">Newest first</option>
                <option value="oldest">Oldest first</option>
                <option value="severity">By severity</option>
              </select>
            </div>
          </div>

          <form
            className="relative max-w-md"
            onSubmit={(e) => {
              e.preventDefault()
              update({ q: draft || null })
            }}
          >
            <IconSearch
              size={14}
              className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-mute-400"
            />
            <input
              className="field w-full pl-8"
              placeholder="Search title, description, IP address…"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
            />
          </form>
        </div>
      </Panel>

      {list.loading && !list.data ? (
        <PageLoader rows={8} />
      ) : list.error && !list.data ? (
        <ErrorState error={list.error} onRetry={list.refetch} />
      ) : items.length === 0 ? (
        <EmptyState
          icon={<IconBell size={20} />}
          title="No alerts match these filters"
          description="Clear the filters to see the full console, or score a CSV on the AI Detection page to generate fresh model alerts."
          action={
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => {
                setDraft('')
                setParams(new URLSearchParams(), { replace: true })
              }}
            >
              Clear filters
            </button>
          }
        />
      ) : (
        <Panel bodyClassName="px-0 py-0">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-[12.5px]">
              <thead className="bg-ink-850">
                <tr className="label-mono border-b border-ink-600/70">
                  <th className="px-5 py-3 font-normal">Severity</th>
                  <th className="px-3 py-3 font-normal">Incident</th>
                  <th className="px-3 py-3 font-normal">Endpoints</th>
                  <th className="px-3 py-3 font-normal">Confidence</th>
                  <th className="px-3 py-3 font-normal">Status</th>
                  <th className="px-3 py-3 font-normal">Origin</th>
                  <th className="px-5 py-3 text-right font-normal">Occurred</th>
                </tr>
              </thead>
              <tbody>
                {items.map((a) => (
                  <tr
                    key={a.id}
                    className="table-row-hover cursor-pointer border-b border-ink-700/50"
                    onClick={() => setSelected(a.id)}
                  >
                    <td className="px-5 py-3">
                      <SeverityBadge severity={a.severity} />
                    </td>
                    <td className="px-3 py-3">
                      <div className="font-medium text-slate-200">{a.title}</div>
                      <div className="text-[11px] text-mute-400">
                        #{a.id} · {a.category_label}
                      </div>
                    </td>
                    <td className="px-3 py-3 font-mono text-[11.5px] text-mute-300">
                      {a.source_ip ?? '—'}
                      <span className="text-mute-400/70"> → </span>
                      {a.destination_ip ?? '—'}
                    </td>
                    <td className="px-3 py-3 tabular-nums text-mute-200">
                      {a.confidence != null ? formatPercent(a.confidence, 1) : '—'}
                    </td>
                    <td className="px-3 py-3" onClick={(e) => e.stopPropagation()}>
                      <select
                        className="field py-1 text-[11.5px]"
                        value={a.status}
                        onChange={(e) => changeStatus(a.id, e.target.value as AlertStatus)}
                      >
                        <option value="open">open</option>
                        <option value="acknowledged">acknowledged</option>
                        <option value="resolved">resolved</option>
                      </select>
                    </td>
                    <td className="px-3 py-3">
                      <OriginBadge origin={a.origin} title={a.origin_detail ?? undefined} />
                    </td>
                    <td className="px-5 py-3 text-right">
                      <div className="text-[12px] text-mute-300">
                        {relativeTime(a.occurred_at ?? a.created_at)}
                      </div>
                      <div className="font-mono text-[10.5px] text-mute-400">
                        {formatTimestamp(a.occurred_at ?? a.created_at)}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="px-5 pb-4">
            <Pagination
              page={list.data?.page ?? 1}
              pages={list.data?.pages ?? 1}
              total={list.data?.total ?? 0}
              onPage={(next) => update({ page: String(next) })}
            />
          </div>
        </Panel>
      )}

      {selected !== null ? (
        <AlertDetailModal
          id={selected}
          onClose={() => setSelected(null)}
          onStatus={(next) => changeStatus(selected, next)}
          onNavigate={(delta) => {
            const current = Number(params.get('page') ?? 1)
            const pages = list.data?.pages ?? 1
            const next = Math.min(pages, Math.max(1, current + delta))
            update({ page: String(next) })
          }}
        />
      ) : null}
    </div>
  )
}

function AlertDetailModal({
  id,
  onClose,
  onStatus,
  onNavigate,
}: {
  id: number
  onClose: () => void
  onStatus: (status: AlertStatus) => void
  onNavigate: (delta: number) => void
}) {
  const { data, error, loading } = useApi<AlertDetail>(`/api/alerts/${id}`)

  return (
    <div
      className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/70 p-4 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="panel panel-glow mt-6 w-full max-w-3xl"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        {loading ? (
          <div className="p-6">
            <PageLoader rows={3} />
          </div>
        ) : error ? (
          <div className="p-6">
            <ErrorState error={error} onRetry={onClose} />
          </div>
        ) : data ? (
          <>
            <header className="flex items-start justify-between gap-4 border-b border-ink-600/70 px-5 py-4">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <SeverityBadge severity={data.alert.severity} />
                  <StatusBadge status={data.alert.status} />
                  <OriginBadge origin={data.alert.origin} />
                  <Badge className="border-ink-600 bg-ink-800 text-mute-300">#{data.alert.id}</Badge>
                </div>
                <h3 className="mt-2 text-base font-semibold text-white">{data.alert.title}</h3>
                <p className="mt-1 font-mono text-[11.5px] text-mute-400">
                  {formatTimestamp(data.alert.occurred_at ?? data.alert.created_at)}
                </p>
              </div>
              <button type="button" className="btn btn-ghost" onClick={onClose}>
                Close
              </button>
            </header>

            <div className="space-y-4 px-5 py-4">
              <section>
                <div className="label-mono mb-1.5">Description</div>
                <p className="text-[12.5px] leading-relaxed text-mute-200">
                  {data.alert.description ?? 'No description recorded.'}
                </p>
                {data.alert.origin_detail ? (
                  <p className="mt-1.5 text-[11px] text-mute-400">
                    Provenance: {data.alert.origin_detail}
                  </p>
                ) : null}
              </section>

              <section className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                <Cell label="Source IP" value={data.alert.source_ip ?? '—'} mono />
                <Cell label="Destination IP" value={data.alert.destination_ip ?? '—'} mono />
                <Cell label="Confidence" value={data.alert.confidence != null ? formatPercent(data.alert.confidence, 2) : '—'} />
                <Cell label="Category" value={data.alert.category_label} />
              </section>

              {data.prediction ? (
                <section className="rounded-xl border border-cyan-400/25 bg-cyan-400/5 px-4 py-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="label-mono">Model output</div>
                    <span className="font-mono text-[11px] text-mute-400">
                      {data.alert.origin === 'model' ? 'trained classifier' : 'n/a'}
                    </span>
                  </div>
                  <p className="mt-1.5 text-[13px] text-slate-200">
                    {data.prediction.predicted_label}
                    {data.prediction.confidence != null
                      ? ` · ${formatPercent(data.prediction.confidence, 2)}`
                      : ''}
                  </p>
                  {data.prediction.probabilities ? (
                    <ul className="mt-2 space-y-1 text-[11px]">
                      {Object.entries(data.prediction.probabilities)
                        .sort((a, b) => b[1] - a[1])
                        .slice(0, 5)
                        .map(([cls, p]) => (
                          <li key={cls} className="flex items-center gap-2">
                            <span className="w-24 truncate text-mute-400">{cls}</span>
                            <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-ink-700">
                              <span
                                className="block h-full rounded-full bg-cyan-400/80"
                                style={{ width: `${p * 100}%` }}
                              />
                            </span>
                            <span className="w-11 text-right tabular-nums text-mute-300">
                              {(p * 100).toFixed(1)}%
                            </span>
                          </li>
                        ))}
                    </ul>
                  ) : null}
                </section>
              ) : null}

              <section>
                <div className="label-mono mb-2">
                  Historical records for this endpoint pair ({data.related_alerts.length})
                </div>
                {data.related_alerts.length === 0 ? (
                  <p className="text-[12px] text-mute-400">
                    No other alerts involve these endpoints.
                  </p>
                ) : (
                  <ul className="max-h-56 space-y-1.5 overflow-y-auto pr-1">
                    {data.related_alerts.map((r) => (
                      <li
                        key={r.id}
                        className="rounded-lg border border-ink-600 bg-ink-850/60 px-3 py-2"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="flex items-center gap-2">
                            <SeverityBadge severity={r.severity} />
                            <span className="text-[12px] text-slate-200">{r.title}</span>
                          </span>
                          <span className="text-[10.5px] text-mute-400">
                            {formatTimestamp(r.occurred_at ?? r.created_at)}
                          </span>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-ink-600/70 pt-3">
                <div className="flex flex-wrap gap-2">
                  <button
                    type="button"
                    className="btn btn-ghost"
                    onClick={() => onStatus('acknowledged')}
                    disabled={data.alert.status === 'acknowledged'}
                  >
                    Acknowledge
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    onClick={() => onStatus('resolved')}
                    disabled={data.alert.status === 'resolved'}
                  >
                    Mark resolved
                  </button>
                  <button
                    type="button"
                    className="btn btn-ghost"
                    onClick={() => onStatus('open')}
                    disabled={data.alert.status === 'open'}
                  >
                    Reopen
                  </button>
                </div>
                <div className="flex gap-2">
                  <button type="button" className="btn btn-ghost" onClick={() => onNavigate(-1)}>
                    Newer page
                  </button>
                  <button type="button" className="btn btn-ghost" onClick={() => onNavigate(1)}>
                    Older page
                  </button>
                </div>
              </footer>
            </div>
          </>
        ) : null}
      </div>
    </div>
  )
}

function Cell({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="rounded-lg border border-ink-600 bg-ink-850/60 px-3 py-2">
      <div className="label-mono">{label}</div>
      <div className={`mt-0.5 text-[12.5px] text-slate-200 ${mono ? 'font-mono' : ''}`}>
        {value}
      </div>
    </div>
  )
}

function SummaryTile({
  label,
  value,
  tone,
}: {
  label: string
  value: number
  tone: 'cyan' | 'rose' | 'amber' | 'sky' | 'emerald'
}) {
  const tones = {
    cyan: 'text-cyan-300',
    rose: 'text-rose-300',
    amber: 'text-amber-300',
    sky: 'text-sky-300',
    emerald: 'text-emerald-300',
  }
  return (
    <div className="panel px-4 py-3.5">
      <div className="label-mono">{label}</div>
      <div className={`mt-1.5 text-2xl font-semibold tabular-nums ${tones[tone]}`}>
        {value.toLocaleString()}
      </div>
    </div>
  )
}

function formatNumberSafe(value: number | undefined): string {
  return (value ?? 0).toLocaleString()
}
