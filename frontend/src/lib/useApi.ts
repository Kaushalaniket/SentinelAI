import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api } from './api'

export interface ApiState<T> {
  data: T | null
  error: ApiError | null
  loading: boolean
  refetch: () => void
  setData: (updater: T | ((current: T | null) => T | null)) => void
}

/**
 * Fetch a JSON endpoint and expose { data, loading, error, refetch }.
 *
 * - Aborts in-flight requests when the path changes (no stale-render flashes).
 * - A `null` path means "not ready yet" and stays idle.
 */
export function useApi<T>(path: string | null): ApiState<T> {
  const [data, setDataState] = useState<T | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState<boolean>(path !== null)
  const [version, setVersion] = useState(0)
  const firstRun = useRef(true)

  useEffect(() => {
    if (path === null) {
      setLoading(false)
      return
    }
    const controller = new AbortController()
    let active = true
    // Skip the spinner on background refreshes so tables do not flash.
    if (!firstRun.current) setLoading(true)
    firstRun.current = false

    api
      .get<T>(path, controller.signal)
      .then((result) => {
        if (!active) return
        setDataState(result)
        setError(null)
      })
      .catch((err: unknown) => {
        if (!active || (err as Error)?.name === 'AbortError') return
        setError(err instanceof ApiError ? err : new ApiError(0, String(err)))
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [path, version])

  const refetch = useCallback(() => setVersion((v) => v + 1), [])
  const setData = useCallback(
    (updater: T | ((current: T | null) => T | null)) =>
      setDataState((current) =>
        typeof updater === 'function' ? (updater as (c: T | null) => T | null)(current) : updater,
      ),
    [],
  )

  return { data, error, loading, refetch, setData }
}
