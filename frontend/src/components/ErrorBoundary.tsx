import { Component, type ErrorInfo, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

/**
 * Catches render-time exceptions so a bug in one page shows an explanation
 * and a reload button instead of a blank white screen during a demo.
 */
export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('SentinelAI render error:', error, info.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    return (
      <div className="flex min-h-screen items-center justify-center p-6">
        <div className="panel max-w-xl px-6 py-6">
          <div className="label-mono">Render error</div>
          <h1 className="mt-2 text-lg font-semibold text-white">
            The console hit an unexpected problem
          </h1>
          <p className="mt-2 text-xs leading-relaxed text-mute-400">
            This is a front-end exception, not a backend failure. Reload the page; if it keeps
            happening, check the browser console and the FastAPI log.
          </p>
          <pre className="mt-3 overflow-x-auto rounded-lg border border-rose-400/30 bg-ink-950 px-3 py-2 font-mono text-[11px] text-rose-300">
            {error.message}
          </pre>
          <button
            type="button"
            className="btn btn-primary mt-4"
            onClick={() => window.location.reload()}
          >
            Reload console
          </button>
        </div>
      </div>
    )
  }
}
