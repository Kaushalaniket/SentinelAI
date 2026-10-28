/** Presentation helpers shared by every page. */

import type { Severity, Verdict } from './types'

export const SEVERITY_ORDER: Severity[] = ['critical', 'high', 'medium', 'low', 'info']

export function formatNumber(value: number | null | undefined, digits = 0): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return value.toLocaleString(undefined, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  })
}

export function formatPercent(value: number | null | undefined, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return `${(value * 100).toFixed(digits)}%`
}

export function formatBytes(value: number | null | undefined): string {
  if (!value && value !== 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let n = Number(value)
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i += 1
  }
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '—'
  if (ms < 1) return `${(ms * 1000).toFixed(0)} µs`
  if (ms < 1000) return `${ms.toFixed(1)} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`
  return `${(ms / 60_000).toFixed(1)} min`
}

/** ISO timestamp -> "3 min ago" (or an absolute date when older than 7 days). */
export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return '—'
  const diff = Date.now() - then
  const abs = Math.abs(diff)
  const future = diff < 0
  const suffix = future ? 'from now' : 'ago'

  const sec = Math.round(abs / 1000)
  if (sec < 45) return future ? `in ${sec}s` : `${sec}s ago`
  const min = Math.round(sec / 60)
  if (min < 60) return `${min} min ${suffix}`
  const hr = Math.round(min / 60)
  if (hr < 24) return `${hr} hr ${suffix}`
  const days = Math.round(hr / 24)
  if (days <= 7) return `${days} day${days === 1 ? '' : 's'} ${suffix}`
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

export function formatTimestamp(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return String(iso)
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  })
}

export const SEVERITY_STYLES: Record<Severity, string> = {
  critical: 'bg-rose-500/15 text-rose-300 border-rose-500/40',
  high: 'bg-orange-500/15 text-orange-300 border-orange-500/40',
  medium: 'bg-amber-500/15 text-amber-300 border-amber-500/40',
  low: 'bg-sky-500/15 text-sky-300 border-sky-500/40',
  info: 'bg-cyan-500/15 text-cyan-300 border-cyan-500/40',
}

export const SEVERITY_DOT: Record<Severity, string> = {
  critical: 'bg-rose-500',
  high: 'bg-orange-400',
  medium: 'bg-amber-400',
  low: 'bg-sky-400',
  info: 'bg-cyan-400',
}

export const STATUS_STYLES: Record<string, string> = {
  open: 'bg-rose-500/12 text-rose-300 border-rose-500/35',
  acknowledged: 'bg-amber-500/12 text-amber-300 border-amber-500/35',
  resolved: 'bg-emerald-500/12 text-emerald-300 border-emerald-500/35',
}

export const VERDICT_STYLES: Record<Verdict, { badge: string; dot: string; label: string }> = {
  malicious: {
    badge: 'bg-rose-500/15 text-rose-300 border-rose-500/45',
    dot: 'bg-rose-500',
    label: 'Malicious',
  },
  suspicious: {
    badge: 'bg-orange-500/15 text-orange-300 border-orange-500/45',
    dot: 'bg-orange-400',
    label: 'Suspicious',
  },
  unknown: {
    badge: 'bg-slate-500/15 text-slate-300 border-slate-500/45',
    dot: 'bg-slate-400',
    label: 'Not flagged',
  },
  private: {
    badge: 'bg-sky-500/15 text-sky-300 border-sky-500/45',
    dot: 'bg-sky-400',
    label: 'Private range',
  },
}

/** Readable label for an origin tag. */
export function originLabel(origin: string): string {
  switch (origin) {
    case 'dataset':
      return 'CIC-IDS2017'
    case 'simulated':
      return 'Simulated'
    case 'upload':
      return 'Uploaded'
    case 'model':
      return 'Model'
    default:
      return origin
  }
}

export const ORIGIN_STYLES: Record<string, string> = {
  dataset: 'bg-teal-500/12 text-teal-300 border-teal-500/35',
  simulated: 'bg-violet-500/14 text-violet-300 border-violet-500/40',
  upload: 'bg-indigo-500/12 text-indigo-300 border-indigo-500/35',
  model: 'bg-cyan-500/12 text-cyan-300 border-cyan-500/35',
}

/** Chart palette keyed by attack category. */
export const CATEGORY_COLORS: Record<string, string> = {
  benign: '#22d3ee',
  ddos: '#f43f5e',
  dos: '#fb7185',
  portscan: '#f59e0b',
  webattack: '#a78bfa',
  bruteforce: '#f97316',
  bot: '#34d399',
  infiltration: '#ef4444',
  heartbleed: '#e11d48',
}

export function categoryColor(category: string): string {
  return CATEGORY_COLORS[category] ?? '#64748b'
}

export function truncate(value: string | null | undefined, max = 60): string {
  if (!value) return '—'
  return value.length > max ? `${value.slice(0, max)}…` : value
}
