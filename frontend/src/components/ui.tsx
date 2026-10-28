import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import {
  ORIGIN_STYLES,
  SEVERITY_STYLES,
  STATUS_STYLES,
  originLabel,
} from '../lib/format'
import type { AlertStatus, Severity } from '../lib/types'

/* ------------------------------------------------------------------ */
/* Loading                                                             */
/* ------------------------------------------------------------------ */
export function Spinner({ label = 'Loading…', size = 16 }: { label?: string; size?: number }) {
  return (
    <span className="inline-flex items-center gap-2 text-mute-400 text-xs">
      <span
        className="inline-block animate-spin rounded-full border-2 border-cyan-400/25 border-t-cyan-400"
        style={{ width: size, height: size }}
        aria-hidden
      />
      {label}
    </span>
  )
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`skeleton ${className}`} aria-hidden />
}

export function PageLoader({ rows = 5 }: { rows?: number }) {
  return (
    <div className="space-y-3" role="status" aria-live="polite">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <Skeleton key={i} className="h-24" />
        ))}
      </div>
      <Skeleton className="h-8 w-56" />
      <div className="space-y-2">
        {Array.from({ length: rows }).map((_, i) => (
          <Skeleton key={i} className="h-11 w-full" />
        ))}
      </div>
      <Spinner label="Loading SentinelAI data…" />
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Empty / error                                                       */
/* ------------------------------------------------------------------ */
export function EmptyState({
  icon = '◎',
  title,
  description,
  action,
}: {
  icon?: ReactNode
  title: string
  description: ReactNode
  action?: ReactNode
}) {
  return (
    <div className="panel flex flex-col items-center justify-center gap-3 px-6 py-14 text-center">
      <div className="flex h-12 w-12 items-center justify-center rounded-xl border border-cyan-400/25 bg-cyan-400/10 text-xl text-cyan-300">
        {icon}
      </div>
      <h3 className="text-sm font-semibold text-slate-100">{title}</h3>
      <p className="max-w-lg text-xs leading-relaxed text-mute-400">{description}</p>
      {action ? <div className="mt-1 flex flex-wrap justify-center gap-2">{action}</div> : null}
    </div>
  )
}

export function ErrorState({
  title = 'Something went wrong',
  error,
  onRetry,
}: {
  title?: string
  error: { message: string; status?: number } | string | null
  onRetry?: () => void
}) {
  const message = typeof error === 'string' ? error : (error?.message ?? 'Unknown error')
  const status = typeof error === 'object' && error ? error.status : undefined
  return (
    <div
      className="panel border-rose-500/35 px-5 py-4"
      role="alert"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <p className="text-sm font-semibold text-rose-300">
            {title}
            {status ? <span className="ml-2 font-mono text-xs text-rose-400/70">HTTP {status}</span> : null}
          </p>
          <p className="max-w-3xl break-words text-xs leading-relaxed text-mute-300">{message}</p>
        </div>
        {onRetry ? (
          <button type="button" className="btn btn-ghost" onClick={onRetry}>
            Retry
          </button>
        ) : null}
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Cards / layout                                                      */
/* ------------------------------------------------------------------ */
export function Panel({
  title,
  subtitle,
  actions,
  children,
  className = '',
  bodyClassName = '',
}: {
  title?: ReactNode
  subtitle?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
  bodyClassName?: string
}) {
  return (
    <section className={`panel ${className}`}>
      {title || actions ? (
        <header className="flex flex-wrap items-start justify-between gap-3 border-b border-ink-600/70 px-5 py-4">
          <div>
            {title ? <h2 className="text-sm font-semibold text-slate-100">{title}</h2> : null}
            {subtitle ? (
              <p className="mt-1 text-xs text-mute-400">{subtitle}</p>
            ) : null}
          </div>
          {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
        </header>
      ) : null}
      <div className={`px-5 py-4 ${bodyClassName}`}>{children}</div>
    </section>
  )
}

export function PageHeader({
  title,
  description,
  actions,
  breadcrumb,
}: {
  title: string
  description?: ReactNode
  actions?: ReactNode
  breadcrumb?: string
}) {
  return (
    <div className="mb-5 flex flex-wrap items-end justify-between gap-4">
      <div>
        {breadcrumb ? <div className="label-mono mb-1">{breadcrumb}</div> : null}
        <h1 className="text-xl font-semibold tracking-tight text-white">{title}</h1>
        {description ? (
          <p className="mt-1 max-w-3xl text-xs leading-relaxed text-mute-400">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  )
}

export function StatCard({
  label,
  value,
  hint,
  accent = 'cyan',
  icon,
  live,
}: {
  label: string
  value: ReactNode
  hint?: ReactNode
  accent?: 'cyan' | 'rose' | 'amber' | 'emerald' | 'sky'
  icon?: ReactNode
  live?: boolean
}) {
  const accents: Record<string, string> = {
    cyan: 'text-cyan-300 border-cyan-400/25 bg-cyan-400/8',
    rose: 'text-rose-300 border-rose-400/25 bg-rose-400/8',
    amber: 'text-amber-300 border-amber-400/25 bg-amber-400/8',
    emerald: 'text-emerald-300 border-emerald-400/25 bg-emerald-400/8',
    sky: 'text-sky-300 border-sky-400/25 bg-sky-400/8',
  }
  return (
    <div className="panel panel-glow flex items-start justify-between gap-3 px-4 py-4">
      <div className="min-w-0">
        <div className="label-mono flex items-center gap-2">
          {live ? <span className="live-dot h-1.5 w-1.5 rounded-full bg-cyan-400" /> : null}
          {label}
        </div>
        <div className="mt-2 text-2xl font-semibold tracking-tight text-white tabular-nums">
          {value}
        </div>
        {hint ? <div className="mt-1 text-[11px] text-mute-400">{hint}</div> : null}
      </div>
      {icon ? (
        <div
          className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border text-sm ${accents[accent]}`}
        >
          {icon}
        </div>
      ) : null}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Badges                                                              */
/* ------------------------------------------------------------------ */
export function Badge({
  children,
  className = '',
  title,
}: {
  children: ReactNode
  className?: string
  title?: string
}) {
  return (
    <span
      title={title}
      className={`inline-flex items-center gap-1.5 rounded-md border px-1.5 py-0.5 text-[10.5px] font-medium uppercase tracking-wide ${className}`}
    >
      {children}
    </span>
  )
}

export function SeverityBadge({ severity }: { severity: Severity }) {
  return (
    <Badge className={SEVERITY_STYLES[severity] ?? SEVERITY_STYLES.info}>
      <span className={`h-1.5 w-1.5 rounded-full ${severityDot(severity)}`} />
      {severity}
    </Badge>
  )
}

export function severityDot(severity: Severity): string {
  return {
    critical: 'bg-rose-500',
    high: 'bg-orange-400',
    medium: 'bg-amber-400',
    low: 'bg-sky-400',
    info: 'bg-cyan-400',
  }[severity]
}

export function OriginBadge({
  origin,
  title,
}: {
  origin: string
  title?: string
}) {
  return (
    <Badge
      className={ORIGIN_STYLES[origin] ?? 'bg-slate-500/12 text-slate-300 border-slate-500/35'}
      title={title}
    >
      {originLabel(origin)}
    </Badge>
  )
}

export function StatusBadge({ status }: { status: AlertStatus | string }) {
  return (
    <Badge className={STATUS_STYLES[status] ?? 'bg-slate-500/12 text-slate-300 border-slate-500/35'}>
      {status}
    </Badge>
  )
}

export function SimulatedTag({ compact = false }: { compact?: boolean }) {
  return (
    <Badge
      className="border-violet-400/45 bg-violet-500/15 text-violet-200"
      title="Synthetic demo record. Not a real network event."
    >
      ◆ {compact ? 'Sim' : 'Simulated'}
    </Badge>
  )
}

/* ------------------------------------------------------------------ */
/* Notice banner                                                       */
/* ------------------------------------------------------------------ */
export function NoticeBanner({
  tone = 'info',
  title,
  children,
  action,
}: {
  tone?: 'info' | 'warning' | 'danger' | 'success'
  title?: string
  children: ReactNode
  action?: ReactNode
}) {
  const tones = {
    info: 'border-cyan-400/30 bg-cyan-400/8 text-cyan-100',
    warning: 'border-amber-400/35 bg-amber-400/8 text-amber-100',
    danger: 'border-rose-400/35 bg-rose-400/8 text-rose-100',
    success: 'border-emerald-400/35 bg-emerald-400/8 text-emerald-100',
  }
  return (
    <div className={`flex flex-wrap items-start justify-between gap-3 rounded-xl border px-4 py-3 ${tones[tone]}`}>
      <div className="min-w-0">
        {title ? <p className="text-xs font-semibold uppercase tracking-wide">{title}</p> : null}
        <div className="mt-1 text-xs leading-relaxed text-mute-300">{children}</div>
      </div>
      {action ? <div className="shrink-0">{action}</div> : null}
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Pagination                                                          */
/* ------------------------------------------------------------------ */
export function Pagination({
  page,
  pages,
  total,
  onPage,
}: {
  page: number
  pages: number
  total: number
  onPage: (next: number) => void
}) {
  if (total === 0) return null
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-ink-600/70 px-1 pt-3">
      <span className="text-[11px] text-mute-400">
        {total.toLocaleString()} record{total === 1 ? '' : 's'} · page {page} of {pages}
      </span>
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="btn btn-ghost"
          disabled={page <= 1}
          onClick={() => onPage(page - 1)}
        >
          ← Prev
        </button>
        <button
          type="button"
          className="btn btn-ghost"
          disabled={page >= pages}
          onClick={() => onPage(page + 1)}
        >
          Next →
        </button>
      </div>
    </div>
  )
}

/* ------------------------------------------------------------------ */
/* Small form controls                                                 */
/* ------------------------------------------------------------------ */
export function Field({
  label,
  hint,
  children,
  className = '',
}: {
  label: string
  hint?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <label className={`block ${className}`}>
      <span className="label-mono">{label}</span>
      <div className="mt-1.5">{children}</div>
      {hint ? <p className="mt-1 text-[11px] text-mute-400">{hint}</p> : null}
    </label>
  )
}

export function Chip({
  active,
  onClick,
  children,
  count,
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
  count?: number
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-full border px-3 py-1 text-[11px] font-medium transition ${
        active
          ? 'border-cyan-400/60 bg-cyan-400/15 text-cyan-200'
          : 'border-ink-600 bg-ink-800/70 text-mute-400 hover:border-cyan-400/30 hover:text-mute-200'
      }`}
    >
      {children}
      {count !== undefined ? (
        <span className={`ml-1.5 tabular-nums ${active ? 'text-cyan-300/80' : 'text-mute-400/70'}`}>
          {count}
        </span>
      ) : null}
    </button>
  )
}

export function Meter({ value, color = '#22d3ee' }: { value: number; color?: string }) {
  const pct = Math.max(0, Math.min(1, value))
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-ink-700">
      <div
        className="h-full rounded-full transition-[width] duration-500"
        style={{ width: `${pct * 100}%`, background: color }}
      />
    </div>
  )
}

export function CopyButton({ text }: { text: string }) {
  return (
    <button
      type="button"
      className="text-[10px] uppercase tracking-wide text-mute-400 transition hover:text-cyan-300"
      onClick={() => navigator.clipboard?.writeText(text)}
    >
      copy
    </button>
  )
}

export function ExternalLink({
  to,
  children,
}: {
  to: string
  children: ReactNode
}) {
  return (
    <a
      href={to}
      target="_blank"
      rel="noreferrer"
      className="text-cyan-300 underline decoration-cyan-400/40 underline-offset-2 hover:decoration-cyan-300"
    >
      {children}
    </a>
  )
}

export function AppLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link
      to={to}
      className="text-cyan-300 underline decoration-cyan-400/40 underline-offset-2 hover:decoration-cyan-300"
    >
      {children}
    </Link>
  )
}
