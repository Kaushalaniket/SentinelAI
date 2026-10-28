import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api, ApiError } from '../lib/api'
import { formatBytes, formatNumber, formatPercent, formatTimestamp } from '../lib/format'
import { useApi } from '../lib/useApi'
import type { DetectionSchema, ModelMetrics, UploadResponse } from '../lib/types'
import {
  Badge,
  EmptyState,
  ErrorState,
  NoticeBanner,
  PageHeader,
  PageLoader,
  Panel,
  SeverityBadge,
} from '../components/ui'
import { IconAlert, IconUpload } from '../components/icons'

type Tab = 'metrics' | 'upload' | 'history'

const tooltipStyle = {
  background: '#0a1122',
  border: '1px solid #1e2f4f',
  borderRadius: 10,
  fontSize: 12,
  color: '#e6edf9',
}

export default function Detection() {
  const [tab, setTab] = useState<Tab>('metrics')

  return (
    <div className="space-y-5">
      <PageHeader
        breadcrumb="Machine Learning"
        title="AI Detection"
        description="Evaluate the trained classifier, upload CIC-IDS2017 style CSV files and inspect per-row predictions with their confidence."
        actions={<ModelPill />}
      />

      <nav className="flex flex-wrap gap-1 rounded-xl border border-ink-600 bg-ink-900/70 p-1">
        {(
          [
            ['metrics', 'Model evaluation'],
            ['upload', 'Classify a CSV'],
            ['history', 'Batch history'],
          ] as [Tab, string][]
        ).map(([key, label]) => (
          <button
            key={key}
            type="button"
            onClick={() => setTab(key)}
            className={`rounded-lg px-3.5 py-2 text-[12.5px] font-medium transition ${
              tab === key
                ? 'bg-cyan-400/15 text-cyan-200'
                : 'text-mute-400 hover:bg-ink-800/70 hover:text-mute-200'
            }`}
          >
            {label}
          </button>
        ))}
      </nav>

      {tab === 'metrics' ? <MetricsTab /> : null}
      {tab === 'upload' ? <UploadTab /> : null}
      {tab === 'history' ? <HistoryTab /> : null}
    </div>
  )
}

function ModelPill() {
  const { data } = useApi<{ model: { available: boolean; model_version?: string } }>('/api/model')
  if (!data) return null
  return (
    <Badge
      className={
        data.model.available
          ? 'border-emerald-400/40 bg-emerald-400/10 text-emerald-300'
          : 'border-amber-400/40 bg-amber-400/10 text-amber-300'
      }
      title={data.model.model_version}
    >
      {data.model.available ? `model ${data.model.model_version}` : 'model not trained'}
    </Badge>
  )
}

/* ================================================================== */
/* Tab 1 — evaluation                                                  */
/* ================================================================== */
function MetricsTab() {
  const { data, error, loading, refetch } = useApi<ModelMetrics>('/api/model/metrics')

  if (loading && !data) return <PageLoader />
  if (error) return <ErrorState error={error} onRetry={refetch} />
  if (!data?.available) {
    return (
      <EmptyState
        icon="⚠"
        title="No evaluation report yet"
        description={
          <>
            {data?.reason ?? 'The model has not been trained.'} Open a terminal in{' '}
            <code className="text-cyan-300">backend/</code> and run{' '}
            <code className="text-cyan-300">python -m app.ml.dataset</code> followed by{' '}
            <code className="text-cyan-300">python -m app.ml.train</code>.
          </>
        }
        action={<button className="btn btn-ghost" onClick={refetch}>Re-check</button>}
      />
    )
  }

  const m = data.model
  if (!m) return <ErrorState error="Metrics file has no model block." onRetry={refetch} />

  const classRows = data.class_rows ?? []
  const importances = (data.feature_importances ?? []).slice(0, 12)
  const maxImp = Math.max(...importances.map((f) => f.importance), 0.0001)
  const normalised = data.confusion_matrix_normalised ?? []

  const benchmark = [
    { name: 'Majority-class baseline', accuracy: data.baselines?.majority_class?.accuracy ?? 0, color: '#64748b' },
    { name: 'Logistic regression', accuracy: data.baselines?.logistic_regression?.accuracy ?? 0, color: '#38bdf8' },
    { name: 'Random Forest (SentinelAI)', accuracy: m.accuracy, color: '#22d3ee' },
  ]

  return (
    <div className="space-y-4">
      <NoticeBanner tone="success" title="Metrics are real, not illustrative">
        Every number below was computed on a held-out {formatNumber(m.labels.length)}-class test
        split of <strong className="text-teal-300">CIC-IDS2017</strong> that the model never saw
        during fitting ({formatNumber(m.labels.reduce((s, l) => s + (m.per_class[l]?.support ?? 0), 0))}{' '}
        rows). Model version <code className="text-cyan-300">{data.model_version}</code>, trained{' '}
        {formatTimestamp(data.trained_at)}.
      </NoticeBanner>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <MetricTile label="Accuracy" value={formatPercent(m.accuracy, 2)} tone="cyan" />
        <MetricTile label="Precision (macro)" value={formatPercent(m.precision_macro, 2)} tone="sky" />
        <MetricTile label="Recall (macro)" value={formatPercent(m.recall_macro, 2)} tone="sky" />
        <MetricTile label="F1 (macro)" value={formatPercent(m.f1_macro, 2)} tone="amber" />
        <MetricTile label="F1 (weighted)" value={formatPercent(m.f1_weighted, 2)} tone="emerald" />
      </div>

      <div className="grid gap-4 xl:grid-cols-3">
        <Panel className="xl:col-span-2" title="Per-class precision / recall / F1">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-[12.5px]">
              <thead>
                <tr className="label-mono border-b border-ink-600/70">
                  <th className="py-2.5 pr-3 font-normal">Class</th>
                  <th className="px-3 py-2.5 text-right font-normal">Precision</th>
                  <th className="px-3 py-2.5 text-right font-normal">Recall</th>
                  <th className="px-3 py-2.5 text-right font-normal">F1</th>
                  <th className="px-3 py-2.5 text-right font-normal">Support</th>
                  <th className="pl-3 py-2.5 font-normal">F1 quality</th>
                </tr>
              </thead>
              <tbody>
                {classRows.map((row) => (
                  <tr key={row.label} className="border-b border-ink-700/50">
                    <td className="py-2.5 pr-3">
                      <div className="flex items-center gap-2">
                        {row.severity ? <SeverityBadge severity={row.severity} /> : null}
                        <span className="text-slate-200">{row.label}</span>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-right tabular-nums text-mute-200">
                      {formatPercent(row.precision, 2)}
                    </td>
                    <td className="px-3 py-2.5 text-right tabular-nums text-mute-200">
                      {formatPercent(row.recall, 2)}
                    </td>
                    <td className="px-3 py-2.5 text-right tabular-nums font-medium text-white">
                      {formatPercent(row.f1, 2)}
                    </td>
                    <td className="px-3 py-2.5 text-right tabular-nums text-mute-400">
                      {formatNumber(row.support)}
                    </td>
                    <td className="py-2.5 pl-3">
                      <div className="h-1.5 w-28 overflow-hidden rounded-full bg-ink-700">
                        <div
                          className="h-full rounded-full"
                          style={{
                            width: `${row.f1 * 100}%`,
                            background: row.f1 > 0.9 ? '#22d3ee' : row.f1 > 0.7 ? '#fbbf24' : '#f43f5e',
                          }}
                        />
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-3 text-[11px] leading-relaxed text-mute-400">
            Heartbleed (11 samples) and Infiltration (36 samples) are extremely rare in
            CIC-IDS2017, so their test-split support is only a handful of rows. Those scores are
            reported honestly but should not be treated as statistically reliable — that is a
            property of the dataset, not of this application.
          </p>
        </Panel>

        <Panel title="Confusion matrix" subtitle="Row-normalised: each row sums to 100%">
          <div className="overflow-x-auto">
            <table className="w-full text-[10.5px]">
              <thead>
                <tr>
                  <th className="label-mono p-1.5 text-left font-normal">true ↓ / pred →</th>
                  {m.label_names.map((n) => (
                    <th key={n} className="p-1.5 text-center font-normal text-mute-400">
                      <span className="block truncate" style={{ maxWidth: 64 }} title={n}>
                        {n}
                      </span>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {normalised.map((row, i) => (
                  <tr key={m.labels[i]}>
                    <td className="p-1.5 text-left text-mute-300">{m.label_names[i]}</td>
                    {row.map((v, j) => (
                      <td
                        key={j}
                        className="p-1.5 text-center tabular-nums"
                        style={{
                          background:
                            i === j
                              ? `rgba(34, 211, 238, ${0.1 + v * 0.75})`
                              : `rgba(244, 63, 94, ${Math.min(0.7, v * 3)})`,
                          color: i === j ? '#ecfeff' : v > 0.05 ? '#ffe4e6' : '#334155',
                        }}
                        title={`${m.label_names[i]} predicted as ${m.label_names[j]}: ${formatPercent(v, 2)}`}
                      >
                        {v >= 0.005 ? formatPercent(v, 1) : v > 0 ? '<1%' : '·'}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mt-3 text-[11px] text-mute-400">
            Off-diagonal cells are misclassifications. Bright cyan = correct.
          </p>
        </Panel>
      </div>

      <div className="grid gap-4 xl:grid-cols-3">
        <Panel title="Accuracy vs. baselines" subtitle="Same held-out test split">
          <div className="h-[220px]">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart
                data={benchmark}
                layout="vertical"
                margin={{ top: 6, right: 16, left: 8, bottom: 6 }}
              >
                <CartesianGrid stroke="#16233d" strokeDasharray="3 3" horizontal={false} />
                <XAxis
                  type="number"
                  domain={[0, 1]}
                  tickFormatter={(v: number) => `${Math.round(v * 100)}%`}
                  tick={{ fill: '#7b8db0', fontSize: 11 }}
                  axisLine={{ stroke: '#1e2f4f' }}
                  tickLine={false}
                />
                <YAxis
                  type="category"
                  dataKey="name"
                  width={140}
                  tick={{ fill: '#c3d0e4', fontSize: 11 }}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip
                  contentStyle={tooltipStyle}
                  formatter={(value) => [formatPercent(Number(value), 2), 'Accuracy']}
                />
                <Bar dataKey="accuracy" radius={[0, 6, 6, 0]} barSize={20}>
                  {benchmark.map((b) => (
                    <Cell key={b.name} fill={b.color} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
          <p className="mt-2 text-[11px] leading-relaxed text-mute-400">
            The majority-class baseline shows how little you learn by always guessing “Benign”
            (the training set is imbalanced). The forest beats both baselines.
          </p>
        </Panel>

        <Panel title="Top feature importances" subtitle="Mean decrease in Gini impurity">
          <ul className="space-y-2">
            {importances.map((f) => (
              <li key={f.feature} className="text-[11.5px]">
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-mute-300" title={f.feature}>
                    {f.feature}
                  </span>
                  <span className="font-mono tabular-nums text-mute-400">
                    {formatPercent(f.importance, 2)}
                  </span>
                </div>
                <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-ink-700">
                  <div
                    className="h-full rounded-full bg-cyan-400/80"
                    style={{ width: `${(f.importance / maxImp) * 100}%` }}
                  />
                </div>
              </li>
            ))}
          </ul>
        </Panel>

        <Panel title="Training configuration">
          <dl className="space-y-2 text-[12px]">
            <Row label="Algorithm" value={data.model_type ?? '—'} />
            <Row label="Rows used" value={formatNumber(data.provenance?.rows_used_for_training)} />
            <Row label="Features" value={formatNumber(data.feature_count)} />
            <Row label="Classes" value={formatNumber(m.labels.length)} />
            <Row
              label="Test split"
              value={`${Math.round(Number(data.hyperparameters?.test_size ?? 0.2) * 100)}%`}
            />
            <Row label="Trees" value={String(data.hyperparameters?.n_estimators ?? '—')} />
            <Row label="Max leaf nodes" value={String(data.hyperparameters?.max_leaf_nodes ?? '—')} />
            <Row label="Random seed" value={String(data.seed ?? '—')} />
            <Row label="scikit-learn" value={data.sklearn_version ?? '—'} />
            <Row label="Train time" value={`${formatNumber(m.train_seconds ?? 0, 1)} s`} />
            <Row label="Model size" value={formatBytes(data.model_file_bytes)} />
          </dl>
          <div className="mt-3 rounded-lg border border-ink-600 bg-ink-850/60 px-3 py-2.5">
            <div className="label-mono">Dataset</div>
            <p className="mt-1 text-[11.5px] leading-relaxed text-mute-300">
              {data.provenance?.dataset} — {data.provenance?.publisher}. Sampled{' '}
              {formatNumber(data.provenance?.rows_used_for_training)} of{' '}
              {formatNumber(data.provenance?.full_dataset_rows)} labelled flows.
            </p>
          </div>
        </Panel>
      </div>
    </div>
  )
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3 border-b border-ink-700/50 pb-1.5">
      <dt className="text-mute-400">{label}</dt>
      <dd className="truncate font-mono text-[11.5px] text-slate-200">{value}</dd>
    </div>
  )
}

function MetricTile({
  label,
  value,
  tone,
}: {
  label: string
  value: string
  tone: 'cyan' | 'sky' | 'amber' | 'emerald'
}) {
  const tones = {
    cyan: 'text-cyan-300',
    sky: 'text-sky-300',
    amber: 'text-amber-300',
    emerald: 'text-emerald-300',
  }
  return (
    <div className="panel px-4 py-3.5">
      <div className="label-mono">{label}</div>
      <div className={`mt-1.5 text-2xl font-semibold tabular-nums ${tones[tone]}`}>{value}</div>
    </div>
  )
}

/* ================================================================== */
/* Tab 2 — upload                                                      */
/* ================================================================== */
function UploadTab() {
  const { data: schema, error: schemaError, loading: schemaLoading, refetch } =
    useApi<DetectionSchema>('/api/detect/schema')
  const [busy, setBusy] = useState(false)
  const [dragging, setDragging] = useState(false)
  const [result, setResult] = useState<UploadResponse | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [failureDetail, setFailureDetail] = useState<Record<string, unknown> | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)

  const send = async (file: File) => {
    setBusy(true)
    setFailure(null)
    setFailureDetail(null)
    setResult(null)
    try {
      setResult(await api.upload<UploadResponse>('/api/detect/upload', file))
    } catch (err) {
      if (err instanceof ApiError) {
        setFailure(err.message)
        setFailureDetail(
          typeof err.detail === 'object' && err.detail !== null
            ? (err.detail as Record<string, unknown>)
            : null,
        )
      } else {
        setFailure(err instanceof Error ? err.message : String(err))
      }
    } finally {
      setBusy(false)
    }
  }

  if (schemaLoading && !schema) return <PageLoader rows={4} />
  if (schemaError) return <ErrorState error={schemaError} onRetry={refetch} />

  const alignment = failureDetail?.alignment as
    | { missing?: string[]; matched_count?: number; expected_count?: number; required_min_features?: number }
    | undefined

  return (
    <div className="space-y-4">
      <NoticeBanner tone="info" title="How scoring works">
        Uploaded columns are matched against the {formatNumber(schema?.expected_feature_count ?? 0)}{' '}
        features the forest was trained on. Missing columns and blank /{' '}
        <code>Infinity</code> cells are filled with the median learned during training. A file is
        rejected only when fewer than half the expected features are present.
      </NoticeBanner>

      <div className="grid gap-4 xl:grid-cols-3">
        <div className="space-y-4 xl:col-span-2">
          <div
            onDragOver={(e) => {
              e.preventDefault()
              setDragging(true)
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => {
              e.preventDefault()
              setDragging(false)
              const file = e.dataTransfer.files?.[0]
              if (file) void send(file)
            }}
            className={`panel flex flex-col items-center justify-center gap-3 px-6 py-10 text-center transition ${
              dragging ? 'border-cyan-400/70 bg-cyan-400/5' : ''
            }`}
          >
            <span className="flex h-12 w-12 items-center justify-center rounded-xl border border-cyan-400/30 bg-cyan-400/10 text-cyan-300">
              <IconUpload size={22} />
            </span>
            <div>
              <p className="text-sm font-semibold text-slate-100">Drop a traffic CSV here</p>
              <p className="mt-1 text-[11.5px] text-mute-400">
                CICFlowMeter / CIC-IDS2017 format · comma separated · up to{' '}
                {formatBytes(schema?.limits.max_bytes ?? 0)} and{' '}
                {formatNumber(schema?.limits.max_rows ?? 0)} rows
              </p>
            </div>
            <label className="btn btn-primary cursor-pointer">
              {busy ? 'Scoring…' : 'Choose CSV file'}
              <input
                ref={inputRef}
                type="file"
                accept=".csv,text/csv"
                className="hidden"
                disabled={busy}
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  if (file) void send(file)
                  e.target.value = ''
                }}
              />
            </label>
            {busy ? <p className="text-[11.5px] text-cyan-300">Running inference…</p> : null}
          </div>

          {failure ? (
            <ErrorState title="File rejected" error={failure} onRetry={() => setFailure(null)} />
          ) : null}
          {alignment && alignment.missing && alignment.missing.length > 0 ? (
            <Panel title="Column mismatch" subtitle="These model features were absent and got imputed">
              <div className="flex flex-wrap gap-1.5">
                {alignment.missing.map((f) => (
                  <Badge key={f} className="border-amber-400/35 bg-amber-400/10 text-amber-200">
                    {f}
                  </Badge>
                ))}
              </div>
            </Panel>
          ) : null}

          {result ? <UploadResult result={result} /> : null}
        </div>

        <div className="space-y-4">
          <Panel title="Sample files" subtitle="Ready-made CSVs for testing">
            <ul className="space-y-2">
              {(schema?.samples ?? []).map((s) => (
                <li
                  key={s.filename}
                  className="flex items-center justify-between gap-2 rounded-lg border border-ink-600 bg-ink-850/60 px-3 py-2"
                >
                  <div className="min-w-0">
                    <div className="truncate font-mono text-[11.5px] text-slate-200">
                      {s.filename}
                    </div>
                    <div className="text-[10.5px] text-mute-400">{formatBytes(s.bytes)}</div>
                  </div>
                  <div className="flex shrink-0 gap-1.5">
                    <a className="btn btn-ghost" href={s.url} download>
                      Get
                    </a>
                    <button
                      type="button"
                      className="btn btn-ghost"
                      disabled={busy}
                      onClick={async () => {
                        setBusy(true)
                        setFailure(null)
                        setFailureDetail(null)
                        setResult(null)
                        try {
                          const blob = await fetch(s.url).then((r) => r.blob())
                          const file = new File([blob], s.filename, { type: 'text/csv' })
                          setResult(await api.upload<UploadResponse>('/api/detect/upload', file))
                        } catch (err) {
                          setFailure(
                            err instanceof ApiError ? err.message : String((err as Error).message),
                          )
                        } finally {
                          setBusy(false)
                        }
                      }}
                    >
                      Score it
                    </button>
                  </div>
                </li>
              ))}
              {schema?.samples?.length === 0 ? (
                <li className="text-[11.5px] text-mute-400">
                  No sample files found in <code>backend/data/samples</code>.
                </li>
              ) : null}
            </ul>
          </Panel>

          <Panel
            title="Expected columns"
            subtitle={`${schema?.expected_feature_count ?? 0} model features`}
          >
            <div className="max-h-72 overflow-y-auto pr-1">
              <div className="flex flex-wrap gap-1.5">
                {(schema?.expected_features ?? []).map((f) => (
                  <span
                    key={f}
                    className="rounded border border-ink-600 bg-ink-850 px-1.5 py-0.5 font-mono text-[10.5px] text-mute-300"
                  >
                    {f}
                  </span>
                ))}
              </div>
            </div>
            <div className="mt-3 border-t border-ink-700/60 pt-2.5">
              <div className="label-mono mb-1.5">Ignored identifier columns</div>
              <div className="flex flex-wrap gap-1.5">
                {(schema?.identifier_columns_ignored ?? []).map((f) => (
                  <span
                    key={f}
                    className="rounded border border-ink-600/70 bg-ink-900 px-1.5 py-0.5 font-mono text-[10.5px] text-mute-400 line-through"
                  >
                    {f}
                  </span>
                ))}
              </div>
            </div>
          </Panel>

          <Panel title="Validation rules">
            <ul className="space-y-1.5 text-[11.5px] leading-relaxed text-mute-400">
              {(schema?.notes ?? []).map((n) => (
                <li key={n} className="flex gap-2">
                  <span className="text-cyan-400">›</span>
                  {n}
                </li>
              ))}
            </ul>
          </Panel>
        </div>
      </div>
    </div>
  )
}

function UploadResult({ result }: { result: UploadResponse }) {
  const correctKnown = result.rows.filter((r) => r.correct !== null)
  const correct = correctKnown.filter((r) => r.correct).length
  const accuracy = correctKnown.length ? correct / correctKnown.length : null

  return (
    <div className="space-y-4">
      <NoticeBanner
        tone="success"
        title={`Scored ${formatNumber(result.rows_scored)} rows from ${result.filename}`}
        action={<Link to="/alerts" className="btn btn-ghost">Open alerts</Link>}
      >
        Model <code className="text-cyan-300">{result.model_version}</code> predicted{' '}
        <strong className="text-rose-300">{formatNumber(result.attack_rows)} attack</strong> and{' '}
        <strong className="text-emerald-300">{formatNumber(result.benign_rows)} benign</strong>{' '}
        rows, creating {formatNumber(result.alerts_created)} alert
        {result.alerts_created === 1 ? '' : 's'}. {result.note}
      </NoticeBanner>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Tile label="Rows scored" value={formatNumber(result.rows_scored)} hint={`${formatNumber(result.rows_received)} received`} />
        <Tile label="Attack rows" value={formatNumber(result.attack_rows)} tone="rose" />
        <Tile
          label="Agreement with CSV label"
          value={accuracy === null ? 'no label column' : formatPercent(accuracy, 1)}
          hint={accuracy === null ? 'ground truth unavailable' : `${correct}/${correctKnown.length} rows`}
          tone="emerald"
        />
        <Tile
          label="Missing cells imputed"
          value={formatNumber(result.missing_values.missing_cells)}
          hint={`${formatNumber(result.missing_values.rows_with_missing)} rows affected`}
          tone="amber"
        />
      </div>

      {result.errors.length > 0 ? (
        <Panel title="Processing notes">
          <ul className="space-y-1.5 text-[11.5px] text-amber-200">
            {result.errors.map((e) => (
              <li key={e} className="flex gap-2">
                <IconAlert size={13} className="mt-0.5 shrink-0 text-amber-300" />
                {e}
              </li>
            ))}
          </ul>
        </Panel>
      ) : null}

      <div className="grid gap-4 xl:grid-cols-3">
        <Panel title="Predicted distribution" subtitle="Model output for this file">
          <ul className="space-y-2">
            {Object.entries(result.predicted_distribution).map(([key, v]) => (
              <li key={key} className="text-[12px]">
                <div className="flex items-center justify-between">
                  <span className="text-mute-300">{v.label}</span>
                  <span className="tabular-nums text-slate-200">{formatNumber(v.count)}</span>
                </div>
                <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-ink-700">
                  <div
                    className="h-full rounded-full bg-cyan-400/80"
                    style={{
                      width: `${(v.count / Math.max(1, result.rows_scored)) * 100}%`,
                    }}
                  />
                </div>
              </li>
            ))}
          </ul>
        </Panel>

        <Panel className="xl:col-span-2" title="Per-row predictions" bodyClassName="px-0 py-0">
          <div className="max-h-[420px] overflow-auto">
            <table className="w-full text-left text-[12px]">
              <thead className="sticky top-0 bg-ink-850">
                <tr className="label-mono border-b border-ink-600/70">
                  <th className="px-5 py-2.5 font-normal">CSV line</th>
                  <th className="px-3 py-2.5 font-normal">Prediction</th>
                  <th className="px-3 py-2.5 text-right font-normal">Confidence</th>
                  <th className="px-3 py-2.5 font-normal">CSV label</th>
                  <th className="px-5 py-2.5 font-normal">Match</th>
                </tr>
              </thead>
              <tbody>
                {result.rows.slice(0, 500).map((r) => (
                  <tr key={r.row} className="border-b border-ink-700/50">
                    <td className="px-5 py-2 font-mono text-[11.5px] text-mute-400">{r.row}</td>
                    <td className="px-3 py-2">
                      <span className={r.is_attack ? 'text-rose-300' : 'text-emerald-300'}>
                        {r.category_label}
                      </span>
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums text-mute-200">
                      {formatPercent(r.confidence, 1)}
                    </td>
                    <td className="px-3 py-2 text-mute-300">{r.truth ?? <span className="text-mute-400/60">—</span>}</td>
                    <td className="px-5 py-2">
                      {r.correct === null ? (
                        <span className="text-mute-400/60">—</span>
                      ) : r.correct ? (
                        <Badge className="border-emerald-400/35 bg-emerald-400/10 text-emerald-300">match</Badge>
                      ) : (
                        <Badge className="border-rose-400/35 bg-rose-400/10 text-rose-300">differs</Badge>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {result.rows.length > 500 ? (
            <p className="px-5 py-2.5 text-[11px] text-mute-400">
              Showing the first 500 of {formatNumber(result.rows.length)} rows. The full result is
              available in Reports → CSV export.
            </p>
          ) : null}
        </Panel>
      </div>
    </div>
  )
}

function Tile({
  label,
  value,
  hint,
  tone = 'cyan',
}: {
  label: string
  value: string
  hint?: string
  tone?: 'cyan' | 'rose' | 'amber' | 'emerald'
}) {
  const tones = {
    cyan: 'text-cyan-300',
    rose: 'text-rose-300',
    amber: 'text-amber-300',
    emerald: 'text-emerald-300',
  }
  return (
    <div className="panel px-4 py-3.5">
      <div className="label-mono">{label}</div>
      <div className={`mt-1.5 text-xl font-semibold tabular-nums ${tones[tone]}`}>{value}</div>
      {hint ? <div className="mt-1 text-[11px] text-mute-400">{hint}</div> : null}
    </div>
  )
}

/* ================================================================== */
/* Tab 3 — history                                                     */
/* ================================================================== */
function HistoryTab() {
  const { data, error, loading, refetch } = useApi<{
    items: {
      id: string
      filename: string
      total_rows: number
      attack_rows: number
      model_version: string
      errors: string[]
      created_at: string
    }[]
  }>('/api/detect/batches')

  if (loading && !data) return <PageLoader rows={4} />
  if (error) return <ErrorState error={error} onRetry={refetch} />

  const items = data?.items ?? []
  if (items.length === 0)
    return (
      <EmptyState
        icon="⏱"
        title="No detection batches yet"
        description="Every CSV you classify is recorded here with its row counts, model version and processing notes."
        action={<Link to="/detection" className="btn btn-primary">Classify a CSV</Link>}
      />
    )

  return (
    <Panel title="Detection batches" subtitle="Newest first" bodyClassName="px-0 py-0">
      <div className="overflow-x-auto">
        <table className="w-full text-left text-[12.5px]">
          <thead>
            <tr className="label-mono border-b border-ink-600/70">
              <th className="px-5 py-3 font-normal">File</th>
              <th className="px-3 py-3 text-right font-normal">Rows</th>
              <th className="px-3 py-3 text-right font-normal">Attack rows</th>
              <th className="px-3 py-3 font-normal">Model</th>
              <th className="px-3 py-3 font-normal">Notes</th>
              <th className="px-5 py-3 font-normal">When</th>
            </tr>
          </thead>
          <tbody>
            {items.map((b) => (
              <tr key={b.id} className="border-b border-ink-700/50">
                <td className="px-5 py-2.5">
                  <div className="font-mono text-[12px] text-slate-200">{b.filename}</div>
                  <div className="font-mono text-[10.5px] text-mute-400">{b.id}</div>
                </td>
                <td className="px-3 py-2.5 text-right tabular-nums text-mute-200">
                  {formatNumber(b.total_rows)}
                </td>
                <td className="px-3 py-2.5 text-right tabular-nums text-rose-300">
                  {formatNumber(b.attack_rows)}
                </td>
                <td className="px-3 py-2.5 font-mono text-[11px] text-mute-400">
                  {b.model_version}
                </td>
                <td className="px-3 py-2.5 text-[11px] text-mute-400">
                  {b.errors?.length ? b.errors.join(' · ') : <span className="text-emerald-400/70">clean</span>}
                </td>
                <td className="px-5 py-2.5 text-[11.5px] text-mute-400">
                  {formatTimestamp(b.created_at)}
                </td>
              </tr>
            ))}      </tbody>
    </table>
      </div>
    </Panel>
  )
}
