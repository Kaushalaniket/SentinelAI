import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { api } from '../lib/api'
import { useApi } from '../lib/useApi'
import type { SettingsPayload } from '../lib/types'
import {
  IconBell,
  IconChip,
  IconClose,
  IconGauge,
  IconGear,
  IconMenu,
  IconNetwork,
  IconPulse,
  IconRadar,
  IconReport,
  IconShield,
} from './icons'
import { Badge } from './ui'

interface NavItem {
  to: string
  label: string
  icon: React.ComponentType<{ size?: number }>
  end?: boolean
}

interface HealthResponse {
  model?: { available?: boolean; version?: string; trained_at?: string }
  database?: { counts?: Record<string, number> }
  bootstrap?: { running?: boolean; error?: string | null }
  live_capture?: boolean
}

const NAV: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: IconGauge, end: true },
  { to: '/live', label: 'Live Monitor', icon: IconPulse },
  { to: '/traffic', label: 'Traffic Analysis', icon: IconNetwork },
  { to: '/detection', label: 'AI Detection', icon: IconChip },
  { to: '/intel', label: 'Threat Intelligence', icon: IconRadar },
  { to: '/alerts', label: 'Alerts', icon: IconBell },
  { to: '/reports', label: 'Reports', icon: IconReport },
  { to: '/settings', label: 'Settings', icon: IconGear },
]

export default function Shell() {
  const [open, setOpen] = useState(false)
  const location = useLocation()
  const { data: settings } = useApi<SettingsPayload>('/api/settings')
  const [health, setHealth] = useState<HealthResponse | null>(null)

  useEffect(() => {
    let active = true
    const load = () =>
      api
        .get<HealthResponse>('/api/health')
        .then((d) => {
          if (active) setHealth(d)
        })
        .catch(() => undefined)
    load()
    const id = window.setInterval(load, 20_000)
    return () => {
      active = false
      window.clearInterval(id)
    }
  }, [])

  useEffect(() => {
    setOpen(false)
  }, [location.pathname])

  const orgName = String(settings?.values?.org_name ?? 'SentinelAI SOC')
  const alerts = health?.database?.counts?.alerts ?? 0
  const modelReady = Boolean(health?.model?.available)

  return (
    <div className="min-h-screen">
      {/* ---------------- mobile backdrop ---------------- */}
      {open ? (
        <div
          className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm lg:hidden"
          onClick={() => setOpen(false)}
          aria-hidden
        />
      ) : null}

      {/* ---------------- sidebar ---------------- */}
      <aside
        className={`fixed inset-y-0 left-0 z-40 flex w-[248px] flex-col border-r border-ink-600/80 bg-ink-900/95 backdrop-blur transition-transform duration-200 lg:translate-x-0 ${
          open ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex items-center justify-between gap-2 px-5 py-5">
          <NavLink to="/" className="flex items-center gap-3">
            <span className="flex h-9 w-9 items-center justify-center rounded-xl border border-cyan-400/40 bg-gradient-to-br from-cyan-400/20 to-cyan-400/5 text-cyan-300">
              <IconShield size={19} />
            </span>
            <span>
              <span className="block text-[15px] font-semibold tracking-tight text-white">
                Sentinel<span className="text-cyan-300">AI</span>
              </span>
              <span className="label-mono block">Intrusion Detection</span>
            </span>
          </NavLink>
          <button
            type="button"
            className="text-mute-400 hover:text-white lg:hidden"
            onClick={() => setOpen(false)}
            aria-label="Close navigation"
          >
            <IconClose size={18} />
          </button>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto px-3 py-2">
          <div className="label-mono px-3 pb-2">Console</div>
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `group relative flex items-center gap-3 rounded-lg px-3 py-2 text-[13px] font-medium transition ${
                  isActive
                    ? 'bg-cyan-400/12 text-cyan-200'
                    : 'text-mute-400 hover:bg-ink-800/70 hover:text-mute-200'
                }`
              }
            >
              {({ isActive }) => (
                <>
                  <span
                    className={`absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-r ${
                      isActive ? 'bg-cyan-400' : 'bg-transparent'
                    }`}
                  />
                  <item.icon size={17} />
                  <span className="flex-1">{item.label}</span>
                  {item.to === '/alerts' && alerts > 0 ? (
                    <span className="rounded-full bg-rose-500/20 px-1.5 py-0.5 text-[10px] tabular-nums text-rose-300">
                      {alerts > 99 ? '99+' : alerts}
                    </span>
                  ) : null}
                </>
              )}
            </NavLink>
          ))}
        </nav>

        <div className="space-y-3 border-t border-ink-600/80 px-4 py-4">
          <div className="rounded-lg border border-ink-600 bg-ink-850/70 px-3 py-2.5">
            <div className="label-mono">Data source</div>
            <p className="mt-1.5 text-[11px] leading-relaxed text-mute-400">
              Live Monitor supports <span className="text-cyan-300">simulated</span> streaming always.
              Real packet capture runs only if scapy + privileges are available.{' '}
              <span className="text-mute-200">Traffic is scored by the trained model.</span>
            </p>
          </div>
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-mute-400">Model</span>
            <Badge
              className={
                modelReady
                  ? 'border-emerald-400/40 bg-emerald-400/10 text-emerald-300'
                  : 'border-amber-400/40 bg-amber-400/10 text-amber-300'
              }
            >
              {modelReady ? 'trained' : 'not trained'}
            </Badge>
          </div>
        </div>
      </aside>

      {/* ---------------- main ---------------- */}
      <div className="lg:pl-[248px]">
        <header className="sticky top-0 z-20 flex items-center gap-3 border-b border-ink-600/80 bg-ink-950/85 px-4 py-3 backdrop-blur-md sm:px-6">
          <button
            type="button"
            className="rounded-md border border-ink-600 p-1.5 text-mute-300 hover:text-white lg:hidden"
            onClick={() => setOpen(true)}
            aria-label="Open navigation"
          >
            <IconMenu size={18} />
          </button>

          <div className="min-w-0 flex-1">
            <div className="truncate text-[13px] font-medium text-slate-200">{orgName}</div>
            <div className="truncate text-[11px] text-mute-400">
              {health?.bootstrap?.running
                ? 'Loading dataset records in the background…'
                : 'Offline demonstration environment · CIC-IDS2017 + simulated demo data'}
            </div>
          </div>

          <div className="flex items-center gap-2">
            <NavLink
              to="/live"
              className="hidden items-center gap-2 rounded-lg border border-ink-600 bg-ink-850/80 px-2.5 py-1.5 text-[11px] text-mute-300 transition hover:border-cyan-400/40 hover:text-cyan-200 sm:inline-flex"
            >
              <IconPulse size={14} className="text-cyan-300" />
              Live Monitor
            </NavLink>
            <NavLink
              to="/settings"
              className="hidden items-center gap-1.5 rounded-lg border border-ink-600 bg-ink-850/80 px-2.5 py-1.5 text-[11px] text-mute-300 transition hover:border-cyan-400/40 hover:text-cyan-200 sm:inline-flex"
            >
              <IconShield size={14} />
              {modelReady ? 'Model ready' : 'Setup needed'}
            </NavLink>
          </div>
        </header>

        <main className="px-4 py-6 sm:px-6 lg:px-8">
          <div className="mx-auto max-w-[1500px]">
            <Outlet />
          </div>
        </main>

        <footer className="border-t border-ink-600/70 px-4 py-5 sm:px-6 lg:px-8">
          <div className="mx-auto flex max-w-[1500px] flex-wrap items-center justify-between gap-3 text-[11px] text-mute-400">
            <span>
              SentinelAI · Final-year CSE project · React + TypeScript + FastAPI + scikit-learn +
              SQLite
            </span>
            <span>
              Machine learning metrics are computed on a held-out test split of CIC-IDS2017.
            </span>
          </div>
        </footer>
      </div>
    </div>
  )
}
