import { useState } from 'react'
import { api, ApiError, qs } from '../lib/api'
import { formatTimestamp, VERDICT_STYLES } from '../lib/format'
import { useApi } from '../lib/useApi'
import type { IntelHistoryItem, IntelLookupResponse } from '../lib/types'
import {
  Badge,
  EmptyState,
  ErrorState,
  NoticeBanner,
  PageHeader,
  PageLoader,
  Panel,
  Field,
} from '../components/ui'
import { IconGlobe, IconRefresh, IconSearch } from '../components/icons'

const QUICK_IPS = ['8.8.8.8', '1.1.1.1', '185.220.101.44', '10.10.0.5', '45.155.205.233']

export default function Intel() {
  const [ip, setIp] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<IntelLookupResponse | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  const status = useApi<{
    sources: IntelLookupResponse['configured_sources']
    feed: { url?: string; entries?: number; age_hours?: number | null; last_feed_update?: string | null; cache_path?: string }
    feed_error?: string | null
    disclaimer: string
    note: string
  }>('/api/intel/status')

  const history = useApi<{ items: IntelHistoryItem[] }>('/api/intel/history?limit=25')

  const lookup = async (target: string) => {
    if (!target.trim()) return
    setBusy(true)
    setFailure(null)
    try {
      setResult(await api.get<IntelLookupResponse>(`/api/intel/lookup${qs({ ip: target.trim() })}`))
      history.refetch()
    } catch (err) {
      setResult(null)
      setFailure(err instanceof ApiError ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const refreshFeed = async () => {
    setRefreshing(true)
    try {
      await api.post('/api/intel/refresh')
      status.refetch()
    } catch (err) {
      setFailure(err instanceof ApiError ? err.message : String(err))
    } finally {
      setRefreshing(false)
    }
  }

  return (
    <div className="space-y-5">
      <PageHeader
        breadcrumb="Threat Intelligence"
        title="IP Reputation Lookup"
        description="Check an IP against live reputation APIs when a key is configured, and always against a cached public blocklist that works with no key and no internet."
        actions={
          <button type="button" className="btn btn-ghost" onClick={refreshFeed} disabled={refreshing}>
            <IconRefresh size={14} />
            {refreshing ? 'Refreshing…' : 'Refresh blocklist'}
          </button>
        }
      />

      <NoticeBanner tone="warning" title="Read this before trusting a result">
        A <strong>not flagged</strong> verdict only means the IP was absent from the checked
        sources. It is <em>not</em> a statement that the address is safe. Live providers are used
        only when their API key is configured; otherwise the app answers from a local cache of a
        public blocklist and says so explicitly.
      </NoticeBanner>

      <div className="grid gap-4 xl:grid-cols-3">
        <div className="space-y-4 xl:col-span-2">
          <Panel title="Lookup an IP address">
            <form
              className="flex flex-wrap items-end gap-3"
              onSubmit={(e) => {
                e.preventDefault()
                void lookup(ip)
              }}
            >
              <Field label="IP address" className="min-w-56 flex-1">
                <input
                  className="field w-full font-mono"
                  placeholder="e.g. 185.220.101.44 or 8.8.8.8"
                  value={ip}
                  onChange={(e) => setIp(e.target.value)}
                />
              </Field>
              <button type="submit" className="btn btn-primary" disabled={busy || !ip.trim()}>
                <IconSearch size={14} />
                {busy ? 'Checking…' : 'Check reputation'}
              </button>
            </form>
            <div className="mt-3 flex flex-wrap items-center gap-1.5">
              <span className="label-mono mr-1">Try</span>
              {QUICK_IPS.map((sample) => (
                <button
                  key={sample}
                  type="button"
                  className="rounded border border-ink-600 bg-ink-850 px-2 py-1 font-mono text-[11px] text-mute-300 transition hover:border-cyan-400/40 hover:text-cyan-300"
                  onClick={() => {
                    setIp(sample)
                    void lookup(sample)
                  }}
                >
                  {sample}
                </button>
              ))}
            </div>
          </Panel>

          {failure ? <ErrorState title="Lookup failed" error={failure} onRetry={() => setFailure(null)} /> : null}

          {result ? (
            <Panel
              title={`Verdict for ${result.ip}`}
              subtitle={`Primary source: ${result.primary_source} · ${result.is_live ? 'live query' : 'offline cache'}`}
              actions={
                <Badge
                  className={
                    result.is_live
                      ? 'border-emerald-400/40 bg-emerald-400/10 text-emerald-300'
                      : 'border-slate-400/40 bg-slate-400/10 text-slate-300'
                  }
                >
                  {result.is_live ? 'live' : 'cached / offline'}
                </Badge>
              }
            >
              <div className="flex flex-wrap items-center gap-3">
                <Badge className={VERDICT_STYLES[result.verdict]?.badge}>
                  <span className={`h-1.5 w-1.5 rounded-full ${VERDICT_STYLES[result.verdict]?.dot}`} />
                  {VERDICT_STYLES[result.verdict]?.label ?? result.verdict}
                </Badge>
                {result.score !== null ? (
                  <span className="text-[12px] text-mute-400">
                    risk score{' '}
                    <span className="font-semibold tabular-nums text-slate-200">{result.score}</span>
                    /100
                  </span>
                ) : null}
                <span className="font-mono text-[11px] text-mute-400">
                  {formatTimestamp(result.queried_at)}
                </span>
              </div>

              <p className="mt-3 text-[12.5px] leading-relaxed text-mute-200">{result.sources[0]?.detail}</p>

              {result.degradations.length > 0 ? (
                <ul className="mt-3 space-y-1">
                  {result.degradations.map((d) => (
                    <li key={d} className="flex gap-2 text-[11.5px] text-amber-200">
                      <span className="text-amber-400">!</span>
                      {d}
                    </li>
                  ))}
                </ul>
              ) : null}

              <div className="mt-4 space-y-3">
                {result.sources.map((s) => (
                  <div
                    key={s.source}
                    className="rounded-xl border border-ink-600 bg-ink-850/60 px-4 py-3"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-[12.5px] font-medium text-slate-200">{s.source}</span>
                      <div className="flex items-center gap-1.5">
                        {s.score !== null ? (
                          <span className="font-mono text-[11px] text-mute-400">
                            score {s.score}
                          </span>
                        ) : null}
                        <Badge className={VERDICT_STYLES[s.verdict]?.badge}>
                          {VERDICT_STYLES[s.verdict]?.label ?? s.verdict}
                        </Badge>
                      </div>
                    </div>
                    <p className="mt-1.5 text-[11.5px] leading-relaxed text-mute-400">{s.detail}</p>
                    {s.feed ? (
                      <p className="mt-1.5 font-mono text-[10.5px] text-mute-400/70">
                        feed: {s.feed.entries?.toLocaleString()} entries · last updated{' '}
                        {s.feed.last_feed_update ?? 'unknown'} · cache {s.feed.age_hours?.toFixed(0)}h
                        old
                      </p>
                    ) : null}
                  </div>
                ))}
              </div>

              <p className="mt-4 rounded-lg border border-ink-600 bg-ink-900/70 px-3 py-2 text-[11px] leading-relaxed text-mute-400">
                {result.disclaimer}
              </p>
            </Panel>
          ) : !failure ? (
            <EmptyState
              icon={<IconGlobe size={20} />}
              title="No lookup performed yet"
              description="Enter an IPv4 or IPv6 address above. SentinelAI will try the configured live providers first, then fall back to its cached public blocklist."
            />
          ) : null}

          <Panel title="Lookup history" subtitle="Stored in SQLite">
            {history.loading && !history.data ? (
              <PageLoader rows={3} />
            ) : (history.data?.items.length ?? 0) === 0 ? (
              <p className="text-[12px] text-mute-400">
                Nothing queried yet. Lookups are recorded here so you can show an examiner a
                repeatable trail.
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-[12px]">
                  <thead>
                    <tr className="label-mono border-b border-ink-600/70">
                      <th className="py-2.5 pr-3 font-normal">IP</th>
                      <th className="px-3 py-2.5 font-normal">Verdict</th>
                      <th className="px-3 py-2.5 font-normal">Source</th>
                      <th className="px-3 py-2.5 font-normal">Mode</th>
                      <th className="py-2.5 pl-3 text-right font-normal">Queried</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.data?.items.map((h) => (
                      <tr key={h.id} className="border-b border-ink-700/50">
                        <td className="py-2 pr-3 font-mono text-slate-200">{h.ip}</td>
                        <td className="px-3 py-2">
                          <Badge className={VERDICT_STYLES[h.verdict]?.badge}>
                            {VERDICT_STYLES[h.verdict]?.label ?? h.verdict}
                          </Badge>
                        </td>
                        <td className="px-3 py-2 text-mute-300">{h.source}</td>
                        <td className="px-3 py-2 text-[11px] text-mute-400">
                          {h.is_live ? 'live' : 'offline cache'}
                        </td>
                        <td className="py-2 pl-3 text-right text-[11px] text-mute-400">
                          {formatTimestamp(h.queried_at)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </div>

        <div className="space-y-4">
          <Panel title="Configured sources">
            {status.loading && !status.data ? (
              <p className="text-[12px] text-mute-400">Loading…</p>
            ) : (
              <ul className="space-y-2.5">
                {(status.data?.sources ?? []).map((s) => (
                  <li
                    key={s.name}
                    className="rounded-lg border border-ink-600 bg-ink-850/60 px-3 py-2.5"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-[12.5px] font-medium text-slate-200">{s.name}</span>
                      <Badge
                        className={
                          s.enabled
                            ? 'border-emerald-400/40 bg-emerald-400/10 text-emerald-300'
                            : 'border-amber-400/40 bg-amber-400/10 text-amber-300'
                        }
                      >
                        {s.enabled ? 'enabled' : 'not configured'}
                      </Badge>
                    </div>
                    <p className="mt-1 text-[11px] leading-relaxed text-mute-400">
                      {s.type === 'remote_api'
                        ? `Live HTTPS API. Requires the ${s.env_var} environment variable.`
                        : `Offline cache of ${s.entries?.toLocaleString() ?? 0} entries.`}
                    </p>
                    {s.error ? (
                      <p className="mt-1 text-[11px] text-rose-300">{s.error}</p>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
            {status.data?.feed_error ? (
              <p className="mt-3 rounded-lg border border-rose-400/30 bg-rose-400/8 px-3 py-2 text-[11px] text-rose-200">
                {status.data.feed_error}
              </p>
            ) : null}
          </Panel>

          <Panel title="Enable live lookups" subtitle="Optional — the app works without them">
            <p className="text-[11.5px] leading-relaxed text-mute-400">
              {status.data?.note ??
                'Set ABUSEIPDB_API_KEY or VIRUSTOTAL_API_KEY to enable live lookups.'}
            </p>
            <ol className="mt-3 space-y-2 text-[11.5px] leading-relaxed text-mute-300">
              <li>
                1. Create a free key at{' '}
                <a
                  className="text-cyan-300 underline decoration-cyan-400/40 underline-offset-2"
                  href="https://www.abuseipdb.com/api"
                  target="_blank"
                  rel="noreferrer"
                >
                  abuseipdb.com/api
                </a>{' '}
                or{' '}
                <a
                  className="text-cyan-300 underline decoration-cyan-400/40 underline-offset-2"
                  href="https://www.virustotal.com/gui/my-apikey"
                  target="_blank"
                  rel="noreferrer"
                >
                  virustotal.com
                </a>
                .
              </li>
              <li>2. Export it before starting the backend:</li>
            </ol>
            <pre className="mt-2 overflow-x-auto rounded-lg border border-ink-600 bg-ink-950 px-3 py-2 font-mono text-[10.5px] text-cyan-200">
{`export ABUSEIPDB_API_KEY="your-key"
uvicorn app.main:app --port 8000`}
            </pre>
            <p className="mt-2 text-[11px] leading-relaxed text-mute-400">
              Keys are read from the environment only — they are never stored in SQLite and never
              sent to the browser.
            </p>
          </Panel>

          <Panel title="Offline blocklist">
            <dl className="space-y-2 text-[12px]">
              <div className="flex items-center justify-between gap-3">
                <dt className="text-mute-400">Entries</dt>
                <dd className="tabular-nums text-slate-200">
                  {status.data?.feed?.entries?.toLocaleString() ?? '—'}
                </dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-mute-400">Cache age</dt>
                <dd className="tabular-nums text-slate-200">
                  {status.data?.feed?.age_hours != null
                    ? `${status.data.feed.age_hours.toFixed(1)} h`
                    : '—'}
                </dd>
              </div>
              <div className="flex items-center justify-between gap-3">
                <dt className="text-mute-400">Feed date</dt>
                <dd className="truncate text-slate-200">
                  {status.data?.feed?.last_feed_update ?? '—'}
                </dd>
              </div>
            </dl>
            <p className="mt-3 font-mono break-all text-[10.5px] leading-relaxed text-mute-400/80">
              {status.data?.feed?.url}
            </p>
            <p className="mt-1 font-mono break-all text-[10.5px] leading-relaxed text-mute-400/60">
              cached at {status.data?.feed?.cache_path}
            </p>
          </Panel>
        </div>
      </div>
    </div>
  )
}
