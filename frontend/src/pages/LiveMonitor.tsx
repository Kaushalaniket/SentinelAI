import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts'
import { api } from '../lib/api'
import { formatTimestamp } from '../lib/format'
import { Badge, EmptyState, NoticeBanner, PageHeader, Panel, SeverityBadge, SimulatedTag, StatCard } from '../components/ui'
import { IconAlert, IconBell, IconChip, IconNetwork, IconPlay, IconPulse, IconSearch, IconStop } from '../components/icons'

interface LiveStatus {
  live_capture_supported: boolean
  scapy_available: boolean
  running_as_root: boolean
  platform: string
  interfaces: string[]
  mode: string
  note: string
}

interface LiveFlow {
  type: 'flow'
  id: string
  timestamp: string
  source_ip: string
  destination_ip: string
  source_port: number
  destination_port: number
  protocol: string
  origin: string
  mode: string
  prediction?: {
    predicted_category: string
    predicted_label: string
    is_attack: boolean
    confidence: number
    severity: string
    probabilities?: Record<string, number>
  }
  alert?: {
    id: string
    severity: string
    title: string
    category: string
    source_ip: string
    destination_ip: string
    confidence: number
    status: string
    occurred_at: string
  }
}

export default function LiveMonitor() {
  const [status, setStatus] = useState<LiveStatus | null>(null)
  const [running, setRunning] = useState(false)
  const [flows, setFlows] = useState<LiveFlow[]>([])
  const [alerts, setAlerts] = useState<any[]>([])
  const [mode, setMode] = useState<'simulated' | 'real'>('simulated')
  const wsRef = useRef<WebSocket | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [filterAttack, setFilterAttack] = useState<'all' | 'attack' | 'benign'>('all')
  const [selectedFlow, setSelectedFlow] = useState<LiveFlow | null>(null)

  const loadStatus = useCallback(async () => {
    try {
      const data = await api.get<LiveStatus>('/api/live/status')
      setStatus(data)
      if (!data.live_capture_supported) {
        setMode('simulated')
      }
    } catch (e: any) {
      setError(e?.message || 'Failed to load live status')
    }
  }, [])

  useEffect(() => {
    loadStatus()
  }, [loadStatus])

  const start = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.close()
    }
    setError(null)
    setFlows([])
    setAlerts([])
    
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const ws = new WebSocket(`${protocol}//${host}/api/live/ws?mode=${mode}`)
    wsRef.current = ws

    ws.onopen = () => {
      setRunning(true)
    }
    ws.onmessage = (ev) => {
      try {
        const data = JSON.parse(ev.data)
        if (data.type === 'flow') {
          setFlows((prev) => [data as LiveFlow, ...prev].slice(0, 200))
          if (data.alert) {
            setAlerts((prev) => [data.alert, ...prev].slice(0, 50))
          }
        } else if (data.type === 'error') {
          setError(data.message)
        }
      } catch {
        // ignore
      }
    }
    ws.onerror = () => {
      setError('WebSocket connection error')
    }
    ws.onclose = () => {
      setRunning(false)
    }
  }, [mode])

  const stop = useCallback(() => {
    if (wsRef.current) {
      wsRef.current.close()
      wsRef.current = null
    }
    setRunning(false)
  }, [])

  useEffect(() => {
    return () => {
      if (wsRef.current) {
        wsRef.current.close()
      }
    }
  }, [])

  const attacks = flows.filter((f) => f.prediction?.is_attack).length
  const totalFlows = flows.length
  const highSeverity = alerts.filter((a) => a.severity === 'high' || a.severity === 'critical').length
  const benignCount = flows.filter((f) => f.prediction && !f.prediction.is_attack).length

  const chartData = useMemo(() => {
    return flows.slice(0, 20).reverse().map((f, idx) => ({
      time: idx + 1,
      confidence: f.prediction ? Math.round(f.prediction.confidence * 100) : 0,
      attack: f.prediction?.is_attack ? 1 : 0,
    }))
  }, [flows])

  const filteredFlows = useMemo(() => {
    return flows.filter((f) => {
      if (filterAttack === 'attack' && !f.prediction?.is_attack) return false
      if (filterAttack === 'benign' && f.prediction?.is_attack) return false
      if (search) {
        const s = search.toLowerCase()
        return (
          f.source_ip.toLowerCase().includes(s) ||
          f.destination_ip.toLowerCase().includes(s) ||
          f.protocol.toLowerCase().includes(s) ||
          (f.prediction?.predicted_label || '').toLowerCase().includes(s)
        )
      }
      return true
    })
  }, [flows, filterAttack, search])

  return (
    <div className="space-y-6">
      <PageHeader
        title="Live Monitor"
        description="Real-time network flow analysis with ML-powered intrusion detection. Stream flows through the trained CIC-IDS2017 model and generate alerts on suspicious activity."
        breadcrumb="SOC Console"
        actions={
          <div className="flex items-center gap-2 flex-wrap">
            {running ? (
              <button type="button" onClick={stop} className="btn btn-ghost">
                <IconStop size={16} /> Stop Monitoring
              </button>
            ) : (
              <button type="button" onClick={start} className="btn btn-primary">
                <IconPlay size={16} /> Start Monitoring
              </button>
            )}
            <Link to="/alerts" className="btn btn-ghost">
              <IconBell size={16} /> View Alerts
            </Link>
          </div>
        }
      />

      <NoticeBanner tone={status?.live_capture_supported ? 'success' : 'warning'} title="Live monitoring mode">
        {status?.live_capture_supported ? (
          <>
            <strong>Real capture possible.</strong> Environment has scapy and root access. You can attempt real packet capture on available interfaces. 
            <strong className="text-amber-300"> Note:</strong> Full CICFlowMeter feature extraction is complex; missing features will be imputed. Always validate results.
          </>
        ) : (
          <>
            <strong>SIMULATED live stream (safe for demo).</strong> Real packet capture requires scapy + Npcap/libpcap + appropriate privileges. 
            Events are clearly labelled as <span className="text-violet-300">SIMULATED</span> and are generated for demonstration - never presented as real network traffic.
          </>
        )}
      </NoticeBanner>

      {error ? <NoticeBanner tone="danger" title="Error">{error}</NoticeBanner> : null}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        <StatCard label="Flows streamed" value={totalFlows.toLocaleString()} icon={<IconNetwork size={18} />} accent="cyan" live={running} />
        <StatCard label="Attacks detected" value={attacks.toLocaleString()} icon={<IconAlert size={18} />} accent="rose" />
        <StatCard label="Benign" value={benignCount.toLocaleString()} icon={<IconChip size={18} />} accent="emerald" />
        <StatCard label="Alerts generated" value={alerts.length.toLocaleString()} icon={<IconBell size={18} />} accent="amber" />
        <StatCard label="High/Critical" value={highSeverity.toLocaleString()} icon={<IconAlert size={18} />} accent="rose" />
      </div>

      <div className="grid gap-6 xl:grid-cols-[1.8fr_1fr]">
        <Panel
          title="Live Traffic Activity"
          subtitle="Real-time confidence scores for streamed flows"
          actions={
            <div className="flex items-center gap-2">
              <select
                value={mode}
                onChange={(e) => setMode(e.target.value as 'simulated' | 'real')}
                disabled={running}
                className="rounded-lg border border-ink-600 bg-ink-850 px-3 py-1.5 text-xs text-slate-200"
              >
                <option value="simulated">SIMULATED MODE</option>
                <option value="real" disabled={!status?.live_capture_supported}>REAL CAPTURE MODE</option>
              </select>
              <Badge className={running ? 'border-emerald-400/40 bg-emerald-400/10 text-emerald-300' : 'border-ink-600 text-mute-400'}>
                <span className={`h-1.5 w-1.5 rounded-full ${running ? 'bg-emerald-400 animate-pulse' : 'bg-mute-500'}`} />
                {running ? 'MONITORING' : 'STOPPED'}
              </Badge>
              {mode === 'simulated' ? <SimulatedTag /> : <Badge className="border-cyan-400/40 bg-cyan-400/10 text-cyan-300">REAL</Badge>}
            </div>
          }
        >
          {flows.length === 0 ? (
            <EmptyState
              icon={<IconPulse size={22} />}
              title={running ? 'Waiting for flows...' : 'Start monitoring to begin'}
              description={
                running
                  ? 'Capturing and analyzing network flows with the ML model.'
                  : 'Click Start Monitoring to begin real-time analysis. Use SIMULATED mode for safe demonstration.'
              }
            />
          ) : (
            <div className="h-[280px] w-full">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1e2f4f" />
                  <XAxis dataKey="time" stroke="#7b8db0" fontSize={10} />
                  <YAxis stroke="#7b8db0" fontSize={10} />
                  <Tooltip
                    contentStyle={{ backgroundColor: '#0a1122', border: '1px solid #1e2f4f', borderRadius: '8px' }}
                    labelStyle={{ color: '#c3d0e4' }}
                  />
                  <Line type="monotone" dataKey="confidence" stroke="#22d3ee" strokeWidth={2} dot={false} name="Confidence %" />
                  <Line type="monotone" dataKey="attack" stroke="#f43f5e" strokeWidth={2} dot={false} name="Attack" />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}
        </Panel>

        <div className="space-y-6">
          <Panel title="Capture Status" subtitle="Interface and permissions">
            <div className="space-y-2 text-xs">
              <div className="flex items-center justify-between">
                <span className="text-mute-400">Status</span>
                <span className={running ? 'text-emerald-300' : 'text-mute-400'}>{running ? 'Active' : 'Inactive'}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-mute-400">Mode</span>
                <span className="text-slate-200 uppercase">{mode}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-mute-400">Real capture</span>
                <span className={status?.live_capture_supported ? 'text-emerald-300' : 'text-amber-300'}>
                  {status?.live_capture_supported ? 'Supported' : 'Not supported'}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-mute-400">Scapy</span>
                <span className={status?.scapy_available ? 'text-emerald-300' : 'text-mute-400'}>
                  {status?.scapy_available ? 'Installed' : 'Missing'}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-mute-400">Privileges</span>
                <span className={status?.running_as_root ? 'text-emerald-300' : 'text-amber-300'}>
                  {status?.running_as_root ? 'Sufficient' : 'Insufficient'}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-mute-400">Platform</span>
                <span className="text-slate-200">{status?.platform || 'Unknown'}</span>
              </div>
              {status?.interfaces && status.interfaces.length > 0 ? (
                <div className="pt-2 border-t border-ink-600/70">
                  <span className="text-mute-400">Interfaces</span>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {status.interfaces.slice(0, 6).map((iface) => (
                      <Badge key={iface} className="border-ink-600 text-mute-400 text-[9px]">{iface}</Badge>
                    ))}
                  </div>
                </div>
              ) : null}
              <div className="pt-2 text-[10px] text-mute-500 leading-relaxed">
                {status?.note}
              </div>
            </div>
          </Panel>
        </div>
      </div>

      <Panel
        title="Live Flow Table"
        subtitle="Searchable and filterable stream of analyzed flows"
        actions={
          <div className="flex items-center gap-2 flex-wrap">
            <div className="relative">
              <IconSearch size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-mute-400" />
              <input
                type="text"
                placeholder="Search IP, protocol, category..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="field pl-8 w-[200px] sm:w-[260px]"
              />
            </div>
            <select
              value={filterAttack}
              onChange={(e) => setFilterAttack(e.target.value as any)}
              className="field"
            >
              <option value="all">All Flows</option>
              <option value="attack">Attacks Only</option>
              <option value="benign">Benign Only</option>
            </select>
          </div>
        }
      >
        {filteredFlows.length === 0 ? (
          <EmptyState
            icon={<IconNetwork size={22} />}
            title="No flows to display"
            description={running ? 'Waiting for matching flows...' : 'Start monitoring to see live flows'}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="min-w-full text-xs">
              <thead>
                <tr className="border-b border-ink-600/70 text-left text-[10px] uppercase tracking-wide text-mute-400">
                  <th className="px-3 py-2">Time</th>
                  <th className="px-3 py-2">Source IP:Port</th>
                  <th className="px-3 py-2">Dest IP:Port</th>
                  <th className="px-3 py-2">Protocol</th>
                  <th className="px-3 py-2">Prediction</th>
                  <th className="px-3 py-2">Confidence</th>
                  <th className="px-3 py-2">Severity</th>
                  <th className="px-3 py-2">Type</th>
                  <th className="px-3 py-2">Action</th>
                </tr>
              </thead>
              <tbody>
                {filteredFlows.map((flow) => (
                  <tr key={flow.id} className="border-b border-ink-700/40 hover:bg-ink-850/40">
                    <td className="px-3 py-2 tabular-nums text-mute-400">{formatTimestamp(flow.timestamp)}</td>
                    <td className="px-3 py-2 text-slate-200">{flow.source_ip}:{flow.source_port}</td>
                    <td className="px-3 py-2 text-slate-200">{flow.destination_ip}:{flow.destination_port}</td>
                    <td className="px-3 py-2 text-mute-300">{flow.protocol}</td>
                    <td className="px-3 py-2">
                      {flow.prediction ? (
                        <span className={flow.prediction.is_attack ? 'text-rose-300 font-medium' : 'text-emerald-300'}>
                          {flow.prediction.predicted_label}
                        </span>
                      ) : (
                        <span className="text-mute-500">-</span>
                      )}
                    </td>
                    <td className="px-3 py-2 tabular-nums text-mute-300">
                      {flow.prediction ? `${(flow.prediction.confidence * 100).toFixed(1)}%` : '-'}
                    </td>
                    <td className="px-3 py-2">
                      {flow.prediction ? <SeverityBadge severity={flow.prediction.severity as any} /> : null}
                    </td>
                    <td className="px-3 py-2">
                      {flow.mode === 'simulated' ? <SimulatedTag compact /> : <Badge className="border-cyan-400/40 bg-cyan-400/10 text-cyan-300">REAL</Badge>}
                    </td>
                    <td className="px-3 py-2">
                      <button
                        type="button"
                        onClick={() => setSelectedFlow(flow)}
                        className="text-cyan-300 hover:text-cyan-200 text-xs"
                      >
                        Details
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <div className="grid gap-6 xl:grid-cols-[1fr_1.2fr]">
        <Panel title="Recent Security Alerts" subtitle="Alerts from live monitoring">
          {alerts.length === 0 ? (
            <EmptyState
              icon={<IconBell size={22} />}
              title="No alerts yet"
              description={running ? 'Monitoring for threats...' : 'Start monitoring to detect threats'}
            />
          ) : (
            <div className="space-y-2 max-h-[400px] overflow-y-auto">
              {alerts.map((alert, idx) => (
                <div key={alert.id || idx} className="rounded-lg border border-rose-400/30 bg-rose-400/5 px-3 py-2.5">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2 flex-wrap">
                        <SeverityBadge severity={alert.severity as any} />
                        <SimulatedTag compact />
                        <Badge className="border-ink-600 text-mute-400 text-[9px]">MODEL</Badge>
                      </div>
                      <p className="mt-1.5 text-xs text-slate-200">{alert.title}</p>
                      <p className="mt-1 text-[10px] text-mute-400">
                        {alert.source_ip} → {alert.destination_ip} · {(alert.confidence * 100).toFixed(1)}% confidence · {alert.status}
                      </p>
                      <p className="mt-1 text-[10px] text-mute-500">{formatTimestamp(alert.occurred_at)}</p>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        </Panel>

        <Panel title="Alert Details Panel" subtitle={selectedFlow ? "Selected flow information" : "Select a flow to view details"}>
          {selectedFlow ? (
            <div className="space-y-3 text-xs">
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <div className="label-mono">Flow ID</div>
                  <div className="mt-1 text-slate-200 font-mono text-[10px] break-all">{selectedFlow.id}</div>
                </div>
                <div>
                  <div className="label-mono">Timestamp</div>
                  <div className="mt-1 text-slate-200">{formatTimestamp(selectedFlow.timestamp)}</div>
                </div>
                <div>
                  <div className="label-mono">Source</div>
                  <div className="mt-1 text-slate-200">{selectedFlow.source_ip}:{selectedFlow.source_port}</div>
                </div>
                <div>
                  <div className="label-mono">Destination</div>
                  <div className="mt-1 text-slate-200">{selectedFlow.destination_ip}:{selectedFlow.destination_port}</div>
                </div>
                <div>
                  <div className="label-mono">Protocol</div>
                  <div className="mt-1 text-slate-200">{selectedFlow.protocol}</div>
                </div>
                <div>
                  <div className="label-mono">Mode</div>
                  <div className="mt-1">{selectedFlow.mode === 'simulated' ? <SimulatedTag compact /> : <Badge className="border-cyan-400/40 bg-cyan-400/10 text-cyan-300">REAL</Badge>}</div>
                </div>
              </div>
              {selectedFlow.prediction ? (
                <div className="pt-3 border-t border-ink-600/70">
                  <div className="label-mono mb-2">ML Prediction</div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <div className="label-mono">Category</div>
                      <div className="mt-1 text-slate-200">{selectedFlow.prediction.predicted_label}</div>
                    </div>
                    <div>
                      <div className="label-mono">Attack</div>
                      <div className="mt-1">{selectedFlow.prediction.is_attack ? <Badge className="border-rose-400/40 bg-rose-400/10 text-rose-300">YES</Badge> : <Badge className="border-emerald-400/40 bg-emerald-400/10 text-emerald-300">NO</Badge>}</div>
                    </div>
                    <div>
                      <div className="label-mono">Confidence</div>
                      <div className="mt-1 text-slate-200">{(selectedFlow.prediction.confidence * 100).toFixed(2)}%</div>
                    </div>
                    <div>
                      <div className="label-mono">Severity</div>
                      <div className="mt-1"><SeverityBadge severity={selectedFlow.prediction.severity as any} /></div>
                    </div>
                  </div>
                </div>
              ) : null}
              {selectedFlow.alert ? (
                <div className="pt-3 border-t border-ink-600/70">
                  <div className="label-mono mb-2">Generated Alert</div>
                  <div className="rounded-lg border border-rose-400/30 bg-rose-400/5 px-3 py-2">
                    <div className="text-slate-200">{selectedFlow.alert.title}</div>
                    <div className="mt-1 text-mute-400">Status: {selectedFlow.alert.status}</div>
                  </div>
                </div>
              ) : null}
            </div>
          ) : (
            <EmptyState
              icon={<IconAlert size={22} />}
              title="No flow selected"
              description="Click Details on any flow in the table to view comprehensive analysis"
            />
          )}
        </Panel>
      </div>
    </div>
  )
}
