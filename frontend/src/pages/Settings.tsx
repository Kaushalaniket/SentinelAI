import { useEffect, useState } from 'react'
import { api, ApiError } from '../lib/api'
import { useApi } from '../lib/useApi'
import type { SettingsPayload } from '../lib/types'
import {
  Badge,
  ErrorState,
  Field,
  NoticeBanner,
  PageHeader,
  PageLoader,
  Panel,
} from '../components/ui'
import { IconRefresh } from '../components/icons'

interface Diagnostics {
  tables: Record<string, number>
  model_available: boolean
  model_reason?: string | null
  dataset_present: boolean
  status: string
}

export default function Settings() {
  const { data, error, loading, refetch } = useApi<SettingsPayload>('/api/settings')
  const diag = useApi<Diagnostics>('/api/settings/diagnostics')
  const [form, setForm] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'success' | 'error'; text: string } | null>(null)

  useEffect(() => {
    if (!data) return
    setForm({
      org_name: String(data.values.org_name ?? ''),
      confidence_threshold: String(data.values.confidence_threshold ?? '0.5'),
      show_simulated_data: String(data.values.show_simulated_data ?? 'true'),
    })
  }, [data])

  if (loading && !data) return <PageLoader />
  if (error && !data) return <ErrorState error={error} onRetry={refetch} />
  if (!data) return <ErrorState error="No settings returned." onRetry={refetch} />

  const dirty = ['org_name', 'confidence_threshold', 'show_simulated_data'].some(
    (key) => String(data.values[key] ?? '') !== form[key],
  )

  const save = async () => {
    setSaving(true)
    setNotice(null)
    try {
      await api.put('/api/settings', form)
      setNotice({ tone: 'success', text: 'Settings saved and applied immediately.' })
      refetch()
      diag.refetch()
    } catch (err) {
      setNotice({
        tone: 'error',
        text: err instanceof ApiError ? err.message : String(err),
      })
    } finally {
      setSaving(false)
    }
  }

  const reset = async () => {
    setSaving(true)
    setNotice(null)
    try {
      await api.post('/api/settings/reset')
      setNotice({ tone: 'success', text: 'All settings restored to their defaults.' })
      refetch()
    } catch (err) {
      setNotice({ tone: 'error', text: err instanceof ApiError ? err.message : String(err) })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-5">
      <PageHeader
        breadcrumb="Configuration"
        title="Settings"
        description="Runtime configuration, environment details and diagnostics. Only three settings exist — all three genuinely change backend behaviour."
        actions={
          <>
            <button type="button" className="btn btn-ghost" onClick={reset} disabled={saving}>
              <IconRefresh size={14} />
              Reset to defaults
            </button>
            <button type="button" className="btn btn-primary" onClick={save} disabled={saving || !dirty}>
              {saving ? 'Saving…' : dirty ? 'Save changes' : 'No changes'}
            </button>
          </>
        }
      />

      {notice ? (
        <NoticeBanner tone={notice.tone === 'success' ? 'success' : 'danger'} title={notice.tone === 'success' ? 'Saved' : 'Error'}>
          {notice.text}
        </NoticeBanner>
      ) : null}

      <div className="grid gap-4 xl:grid-cols-3">
        <Panel className="xl:col-span-2" title="General" subtitle="Applies to every request, no restart needed">
          <div className="space-y-5">
            <Field
              label="Console name"
              hint={`Rendered in the sidebar and header. Affects: ${data.meta.org_name?.affects}`}
            >
              <input
                className="field w-full max-w-md"
                maxLength={60}
                value={form.org_name ?? ''}
                onChange={(e) => setForm({ ...form, org_name: e.target.value })}
              />
            </Field>

            <Field
              label="Alert confidence threshold"
              hint={data.meta.confidence_threshold?.description}
            >
              <div className="flex max-w-md items-center gap-3">
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.05}
                  className="flex-1 accent-cyan-400"
                  value={Number(form.confidence_threshold ?? 0.5)}
                  onChange={(e) => setForm({ ...form, confidence_threshold: e.target.value })}
                />
                <input
                  type="number"
                  min={0}
                  max={1}
                  step={0.05}
                  className="field w-24 text-right"
                  value={form.confidence_threshold ?? '0.5'}
                  onChange={(e) => setForm({ ...form, confidence_threshold: e.target.value })}
                />
              </div>
              <p className="mt-1.5 text-[11px] text-mute-400">
                Predictions below the threshold are still stored and shown, but no alert is created.
                Currently: <span className="text-cyan-300">{form.confidence_threshold}</span>
              </p>
            </Field>

            <div>
              <div className="label-mono">Show simulated demo data</div>
              <label className="mt-2 flex cursor-pointer items-center gap-3">
                <button
                  type="button"
                  role="switch"
                  aria-checked={form.show_simulated_data === 'true'}
                  onClick={() =>
                    setForm({
                      ...form,
                      show_simulated_data: form.show_simulated_data === 'true' ? 'false' : 'true',
                    })
                  }
                  className={`relative h-6 w-11 rounded-full transition ${
                    form.show_simulated_data === 'true' ? 'bg-cyan-500' : 'bg-ink-600'
                  }`}
                >
                  <span
                    className={`absolute top-0.5 h-5 w-5 rounded-full bg-white transition-all ${
                      form.show_simulated_data === 'true' ? 'left-[22px]' : 'left-0.5'
                    }`}
                  />
                </button>
                <span className="text-[12.5px] text-slate-200">
                  {form.show_simulated_data === 'true'
                    ? 'Simulated rows and alerts are visible'
                    : 'Simulated rows and alerts are hidden everywhere'}
                </span>
              </label>
              <p className="mt-2 text-[11px] leading-relaxed text-mute-400">
                {data.meta.show_simulated_data?.description}
              </p>
            </div>
          </div>
        </Panel>

        <div className="space-y-4">
          <Panel title="Model">
            <dl className="space-y-2 text-[12px]">
              <Row label="State" value={data.model.available ? 'trained' : 'missing'} />
              <Row label="Version" value={data.model.model_version ?? '—'} />
            </dl>
            <pre className="mt-3 overflow-x-auto rounded-lg border border-ink-600 bg-ink-950 px-3 py-2 font-mono text-[10.5px] leading-relaxed text-cyan-200">
{`cd backend
python -m app.ml.dataset   # build sample
python -m app.ml.train     # train + evaluate`}
            </pre>
            <p className="mt-2 text-[11px] leading-relaxed text-mute-400">
              Re-running training writes a new <code>model.joblib</code> and{' '}
              <code>metrics.json</code>; the API picks them up on the next request.
            </p>
          </Panel>

          <Panel title="API keys" subtitle="Optional threat-intelligence providers">
            <ul className="space-y-2">
              {data.api_keys.map((k) => (
                <li
                  key={k.env_var}
                  className="flex items-start justify-between gap-3 rounded-lg border border-ink-600 bg-ink-850/60 px-3 py-2"
                >
                  <div className="min-w-0">
                    <div className="font-mono text-[11.5px] text-slate-200">{k.env_var}</div>
                    <div className="text-[11px] text-mute-400">
                      {k.provider} · {k.used_by}
                    </div>
                  </div>
                  <Badge
                    className={
                      k.configured
                        ? 'border-emerald-400/40 bg-emerald-400/10 text-emerald-300'
                        : 'border-amber-400/40 bg-amber-400/10 text-amber-300'
                    }
                  >
                    {k.configured ? 'set' : 'unset'}
                  </Badge>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-[11px] leading-relaxed text-mute-400">
              Keys are read from the process environment only. They are never written to SQLite,
              never exposed through the API and never sent to the browser.
            </p>
          </Panel>
        </div>
      </div>

      <div className="grid gap-4 xl:grid-cols-3">
        <Panel title="Environment">
          <dl className="space-y-2 text-[12px]">
            <Row label="Python" value={String(data.environment.python ?? '—')} />
            <Row label="Dataset" value={String(data.environment.dataset_name ?? '—')} />
            <Row
              label="Dataset archive"
              value={data.environment.dataset_present ? 'present' : 'not downloaded'}
            />
            <Row label="Live capture" value="disabled (by design)" />
            <Row label="SQLite file" value={String(data.environment.database_path ?? '—')} mono />
            <Row label="Model file" value={String(data.environment.model_path ?? '—')} mono />
          </dl>
          <div className="mt-3 rounded-lg border border-ink-600 bg-ink-850/60 px-3 py-2.5">
            <div className="label-mono">Dataset reference</div>
            <p className="mt-1 text-[11px] leading-relaxed text-mute-300">
              Sharafaldin, Lashkari &amp; Ghorbani, “Toward Generating a New Intrusion Detection
              Dataset and Intrusion Traffic Characterization”, ICISSP 2018.{' '}
              <a
                className="text-cyan-300 underline decoration-cyan-400/40 underline-offset-2"
                href={String(data.environment.dataset_url ?? '#')}
                target="_blank"
                rel="noreferrer"
              >
                Official page
              </a>
            </p>
          </div>
        </Panel>

        <Panel title="Diagnostics" subtitle="Read-only health check">
          {diag.loading && !diag.data ? (
            <p className="text-[12px] text-mute-400">Checking…</p>
          ) : diag.error ? (
            <ErrorState error={diag.error} onRetry={diag.refetch} />
          ) : diag.data ? (
            <>
              <div className="mb-3 flex items-center gap-2">
                <Badge
                  className={
                    diag.data.status === 'ok'
                      ? 'border-emerald-400/40 bg-emerald-400/10 text-emerald-300'
                      : 'border-amber-400/40 bg-amber-400/10 text-amber-300'
                  }
                >
                  {diag.data.status}
                </Badge>
                <button type="button" className="text-[11px] text-cyan-300 hover:underline" onClick={diag.refetch}>
                  re-run
                </button>
              </div>
              <table className="w-full text-left text-[12px]">
                <tbody>
                  {Object.entries(diag.data.tables).map(([table, count]) => (
                    <tr key={table} className="border-b border-ink-700/50">
                      <td className="py-1.5 font-mono text-mute-400">{table}</td>
                      <td className="py-1.5 text-right tabular-nums text-slate-200">
                        {count.toLocaleString()}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {diag.data.model_reason ? (
                <p className="mt-2 text-[11px] text-amber-300">{diag.data.model_reason}</p>
              ) : null}
            </>
          ) : null}
        </Panel>

        <Panel title="How to run this project">
          <ol className="space-y-3 text-[11.5px] leading-relaxed text-mute-300">
            <Step n="1" title="Backend">
              <code className="text-cyan-300">cd backend && pip install -r requirements.txt</code>
            </Step>
            <Step n="2" title="Get the dataset">
              <code className="text-cyan-300">python -m scripts.download_dataset</code>
            </Step>
            <Step n="3" title="Train the model">
              <code className="text-cyan-300">python -m app.ml.dataset && python -m app.ml.train</code>
            </Step>
            <Step n="4" title="Serve the API">
              <code className="text-cyan-300">uvicorn app.main:app --port 8000</code>
            </Step>
            <Step n="5" title="Front-end">
              <code className="text-cyan-300">cd ../frontend && npm install && npm run dev</code>
            </Step>
          </ol>
        </Panel>
      </div>
    </div>
  )
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-ink-700/50 pb-1.5">
      <dt className="shrink-0 text-mute-400">{label}</dt>
      <dd
        className={`truncate text-right text-slate-200 ${mono ? 'font-mono text-[10.5px]' : ''}`}
        title={value}
      >
        {value}
      </dd>
    </div>
  )
}

function Step({ n, title, children }: { n: string; title: string; children: React.ReactNode }) {
  return (
    <li className="flex gap-3">
      <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-cyan-400/40 bg-cyan-400/10 text-[10px] font-semibold text-cyan-300">
        {n}
      </span>
      <div className="min-w-0">
        <div className="text-[12px] font-medium text-slate-200">{title}</div>
        <div className="mt-0.5 break-words">{children}</div>
      </div>
    </li>
  )
}
