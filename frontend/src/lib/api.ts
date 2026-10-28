/**
 * Tiny typed HTTP client for the SentinelAI API.
 *
 * Every helper funnels through `request()`, which:
 *  - turns non-2xx responses into an `ApiError` carrying FastAPI's `detail`
 *  - treats a dropped connection as a friendly message instead of a crash
 *  - supports an AbortSignal so pages can cancel stale requests
 */

export class ApiError extends Error {
  status: number
  detail: unknown

  constructor(status: number, detail: unknown) {
    const message =
      typeof detail === 'string'
        ? detail
        : detail && typeof detail === 'object' && 'message' in (detail as object)
          ? String((detail as { message: unknown }).message)
          : JSON.stringify(detail ?? 'Request failed')
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.detail = detail
  }

  /** Field-level validation errors, if FastAPI returned them. */
  get fieldErrors(): Record<string, string> {
    if (!Array.isArray(this.detail)) return {}
    const out: Record<string, string> = {}
    for (const item of this.detail as { loc?: string[]; msg?: string }[]) {
      if (item?.loc && item.msg) out[item.loc.join('.')] = item.msg
    }
    return out
  }
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

async function request<T>(
  path: string,
  options: { method?: Method; body?: unknown; signal?: AbortSignal; raw?: BodyInit } = {},
): Promise<T> {
  const { method = 'GET', body, signal, raw } = options
  let response: Response

  try {
    response = await fetch(path, {
      method,
      signal,
      headers:
        raw !== undefined
          ? undefined
          : body !== undefined
            ? { 'Content-Type': 'application/json' }
            : undefined,
      body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined),
    })
  } catch (err) {
    if ((err as Error)?.name === 'AbortError') throw err
    throw new ApiError(0, 'Cannot reach the SentinelAI backend. Is `uvicorn` still running?')
  }

  if (!response.ok) {
    let detail: unknown = `${response.status} ${response.statusText}`
    try {
      const payload = await response.json()
      detail = payload?.detail ?? payload
    } catch {
      /* non-JSON error body (proxy 502 etc.) */
    }
    throw new ApiError(response.status, detail)
  }

  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'POST', body, signal }),
  put: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'PUT', body, signal }),
  patch: <T>(path: string, body?: unknown, signal?: AbortSignal) =>
    request<T>(path, { method: 'PATCH', body, signal }),
  upload: <T>(path: string, file: File, signal?: AbortSignal) => {
    const form = new FormData()
    form.append('file', file)
    return request<T>(path, { method: 'POST', raw: form, signal })
  },
  /** Absolute URL for a CSV download link. */
  csv: (kind: string, origin?: string) =>
    `/api/reports/export?kind=${encodeURIComponent(kind)}${
      origin ? `&origin=${encodeURIComponent(origin)}` : ''
    }`,
}

/** Build a query string, dropping empty/undefined values. */
export function qs(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '' || value === false) continue
    search.set(key, String(value))
  }
  const str = search.toString()
  return str ? `?${str}` : ''
}
