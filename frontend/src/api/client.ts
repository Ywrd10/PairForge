export class ApiError extends Error {
  constructor(
    message: string,
    readonly status = 0,
    readonly fieldErrors: Record<string, string> = {},
    readonly requestId?: string,
    readonly retryAfter?: string,
    readonly executionId?: string,
    readonly outcomeUnknown?: boolean,
    readonly executionStatus?: string,
    readonly failureReason?: string,
  ) { super(message) }
}

export interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  token?: string
  signal?: AbortSignal
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

// TypeScript types disappear at runtime. Reject broken API payloads before rendering.
export function expectResponse<T>(value: unknown, matches: (value: unknown) => value is T): T {
  if (!matches(value)) throw new ApiError('The server returned an unexpected response. Please try again.')
  return value
}

const baseUrl = (import.meta.env.VITE_API_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '')
export function collaborationUrl() {
  const url = new URL(`${baseUrl}/ws`)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}

// A fixed configured API origin receives credentials, never a navigation URL.
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const timeout = AbortSignal.timeout(15_000)
  const signal = options.signal ? AbortSignal.any([options.signal, timeout]) : timeout
  try {
    const response = await fetch(`${baseUrl}/api${path}`, {
      method: options.method ?? 'GET',
      headers: {
        Accept: 'application/json',
        ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
      },
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      credentials: 'omit', cache: 'no-store', redirect: 'error', signal,
    })
    if (!response.ok) {
      const body: unknown = await response.json().catch(() => null)
      const fields: Record<string, string> = {}
      let message = 'The request could not be completed.'
      if (body && typeof body === 'object') {
        if ('message' in body && typeof body.message === 'string') message = body.message
        if ('fieldErrors' in body && body.fieldErrors && typeof body.fieldErrors === 'object') {
          for (const [key, value] of Object.entries(body.fieldErrors)) {
            if (typeof value === 'string') fields[key] = value
          }
        }
      }
      throw new ApiError(message, response.status, fields,
        response.headers.get('X-Request-ID') ?? undefined,
        response.headers.get('Retry-After') ?? undefined,
        isRecord(body) && typeof body.executionId === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(body.executionId) ? body.executionId : undefined,
        isRecord(body) && typeof body.outcomeUnknown === 'boolean' ? body.outcomeUnknown : undefined,
        isRecord(body) && typeof body.status === 'string' && ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT'].includes(body.status) ? body.status : undefined,
        isRecord(body) && typeof body.failureReason === 'string' && body.failureReason.length <= 64 ? body.failureReason : undefined)
    }
    try { return await response.json() as T }
    catch { throw new ApiError('The server returned an unreadable response.', response.status) }
  } catch (error) {
    if (options.signal?.aborted) throw new DOMException('Request cancelled', 'AbortError')
    if (error instanceof ApiError) throw error
    throw new ApiError(timeout.aborted
      ? 'The request timed out. Its outcome may be uncertain.'
      : 'Cannot reach the server. Check your connection and try again.')
  }
}
