import { setTimeout as delay } from 'node:timers/promises'

export interface Config {
  maxConnections: number; maxJobs: number; maxDurationSeconds: number; deadlineMs: number
  terminalDeadlineMs: number; warmupSeconds: number; measurementSeconds: number
  repetitions: number; jobsPerLanguage: number; stages: number[]; updateIntervalMs: number
  executionBatchIntervalSeconds: number
}
export function config(value: unknown): Config {
  if (!value || typeof value !== 'object') throw new Error('Explicit load bounds are required')
  const v = value as Record<string, unknown>
  const bounds: Record<string, [number, number]> = {
    maxConnections: [2, 10], maxJobs: [2, 60], maxDurationSeconds: [30, 1200], deadlineMs: [100, 10000],
    terminalDeadlineMs: [1000, 120000], warmupSeconds: [1, 30], measurementSeconds: [1, 120],
    repetitions: [1, 3], jobsPerLanguage: [1, 8], updateIntervalMs: [300, 5000], executionBatchIntervalSeconds: [60, 120],
  }
  for (const [key, [min, max]] of Object.entries(bounds)) {
    if (!Number.isSafeInteger(v[key]) || Number(v[key]) < min || Number(v[key]) > max) throw new Error(`Invalid required bound: ${key}`)
  }
  if (!Array.isArray(v.stages) || v.stages.length < 1 || v.stages.length > 3
    || new Set(v.stages).size !== v.stages.length
    || v.stages.some(n => ![2, 5, 10].includes(n) || n > Number(v.maxConnections))) throw new Error('Invalid connection stages')
  if (Number(v.repetitions) * 2 * (Number(v.jobsPerLanguage) + 1) > Number(v.maxJobs)) throw new Error('Workload exceeds maximum jobs including warm-up')
  if (Object.keys(v).some(k => !(k in bounds) && k !== 'stages')) throw new Error('Unknown configuration field')
  return v as unknown as Config
}

export function distribution(samples: number[]) {
  if (samples.some(n => !Number.isFinite(n) || n < 0)) throw new Error('Invalid timing sample')
  const sorted = [...samples].sort((a, b) => a - b)
  const percentile = (p: number) => sorted.length ? sorted[Math.ceil(p * sorted.length) - 1] : null
  return { count: sorted.length, min: sorted[0] ?? null, p50: percentile(.5), p95: percentile(.95),
    p99: percentile(.99), max: sorted.at(-1) ?? null,
    mean: sorted.length ? sorted.reduce((a, b) => a + b, 0) / sorted.length : null }
}
export function elapsed(start: number, end: number) {
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) throw new Error('Invalid monotonic timestamps')
  return end - start
}
export async function until(test: () => boolean, deadlineMs: number, signal: AbortSignal) {
  const stop = performance.now() + deadlineMs
  while (!test()) {
    signal.throwIfAborted()
    if (performance.now() >= stop) throw new Error('Observation deadline exceeded')
    await delay(10, undefined, { signal })
  }
  signal.throwIfAborted()
}
export async function cleanupAll(actions: (() => Promise<unknown>)[]) {
  const failures: unknown[] = []
  for (const action of [...actions].reverse()) { try { await action() } catch (error) { failures.push(error) } }
  if (failures.length) throw new AggregateError(failures, 'Load fixture cleanup failed')
}

// Records events even when they beat the HTTP receipt. Duplicate terminal notifications
// never replace the first observation. The caller resets the journal per bounded batch.
export class Journal {
  private entries = new Map<string, { revision: number; time: number; status: string }>()
  duplicates = 0
  private maximum: number
  private room: string
  constructor(maximum: number, room: string) { this.maximum = maximum; this.room = room }
  observe(room: string, id: string, observer: number, revision: number, status: string, time: number) {
    if (room !== this.room) throw new Error('Wrong room in execution event')
    if (!Number.isSafeInteger(revision) || revision < 0 || !Number.isFinite(time)) throw new Error('Invalid event')
    const key = `${observer}:${id}`, old = this.entries.get(key)
    if (old && (revision <= old.revision || ['SUCCEEDED', 'FAILED', 'TIMED_OUT'].includes(old.status))) { this.duplicates++; return }
    if (!old && this.entries.size >= this.maximum) throw new Error('Event journal bound exceeded')
    this.entries.set(key, { revision, status, time })
  }
  terminal(id: string, observer: number) {
    const event = this.entries.get(`${observer}:${id}`)
    return event && ['SUCCEEDED', 'FAILED', 'TIMED_OUT'].includes(event.status) ? event : undefined
  }
}
export class Deliveries {
  readonly times = new Map<number, number>()
  duplicates = 0
  readonly id: string
  readonly generation: string
  readonly content: string
  readonly sent: number
  readonly observers: number
  constructor(id: string, generation: string, content: string, sent: number, observers: number) {
    this.id = id; this.generation = generation; this.content = content; this.sent = sent; this.observers = observers
  }
  observe(observer: number, id: string | null, generation: string, content: string, time: number) {
    if (id !== this.id) return
    if (generation !== this.generation || content !== this.content || observer < 0 || observer >= this.observers) throw new Error('Mismatched document acknowledgement')
    if (this.times.has(observer)) { this.duplicates++; return }
    this.times.set(observer, elapsed(this.sent, time))
  }
}

export type Submission = { kind: 'accepted'; id: string; latencyMs: number } | { kind: 'rejected'; status: number; latencyMs: number } | { kind: 'uncertain'; latencyMs: number }
export async function submitOnce(send: () => Promise<Response>): Promise<Submission> {
  const start = performance.now()
  try {
    const response = await send()
    if ([400, 401, 403, 404, 409, 413, 429].includes(response.status)) return { kind: 'rejected', status: response.status, latencyMs: elapsed(start, performance.now()) }
    // A 5xx or lost/malformed receipt can follow a committed execution. Never retry.
    if (response.status !== 202) return { kind: 'uncertain', latencyMs: elapsed(start, performance.now()) }
    const body: unknown = await response.json()
    if (!body || typeof body !== 'object' || !('executionId' in body) || typeof body.executionId !== 'string'
      || !/^[0-9a-f-]{36}$/.test(body.executionId)) return { kind: 'uncertain', latencyMs: elapsed(start, performance.now()) }
    return { kind: 'accepted', id: body.executionId, latencyMs: elapsed(start, performance.now()) }
  } catch { return { kind: 'uncertain', latencyMs: elapsed(start, performance.now()) } }
}
