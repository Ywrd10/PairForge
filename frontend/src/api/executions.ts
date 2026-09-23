import type { Session } from '../auth/session'
import type { Language } from './rooms'
import { expectResponse, isRecord } from './client'

export type Status = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT'
export interface ExecutionEvent { schemaVersion: 1; executionId: string; roomId: string; status: Status; stateRevision: number }
export interface Summary { id: string; roomId: string; language: Language; status: Status; stateRevision: number; createdAt: string; failureReason: string | null }
export interface Detail extends Summary { source: string; stdout: string; stderr: string; exitCode: number | null; durationMs: number | null; outputTruncated: boolean }
export interface Receipt { executionId: string; status: Status; stateRevision: number; failureReason: string | null }
interface History { items: Summary[]; page: number; size: number; hasNext: boolean }
export const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
const revision = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
export const terminal = (status: Status) => status === 'SUCCEEDED' || status === 'FAILED' || status === 'TIMED_OUT'
const status = (v: unknown): v is Status => typeof v === 'string' && ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT'].includes(v)
const reason = (v: unknown) => v === null || typeof v === 'string' && v.length <= 64
const bytes = (v: string) => new TextEncoder().encode(v).length
export const validSource = (v: string) => !v.includes('\0') && bytes(v) <= 65536 && new TextDecoder().decode(new TextEncoder().encode(v)) === v
export function isEvent(v: unknown): v is ExecutionEvent {
  return isRecord(v) && v.schemaVersion === 1 && uuid(v.executionId) && uuid(v.roomId) && status(v.status) && revision(v.stateRevision)
}
function summary(v: unknown): v is Summary {
  return isRecord(v) && uuid(v.id) && uuid(v.roomId) && (v.language === 'JAVA' || v.language === 'PYTHON') && status(v.status)
    && revision(v.stateRevision) && typeof v.createdAt === 'string' && Number.isFinite(Date.parse(v.createdAt)) && reason(v.failureReason)
}
function detail(v: unknown): v is Detail {
  return summary(v) && isRecord(v) && typeof v.source === 'string' && validSource(v.source)
    && typeof v.stdout === 'string' && typeof v.stderr === 'string' && validSource(v.stdout) && validSource(v.stderr)
    && bytes(v.stdout) + bytes(v.stderr) <= 65536 && typeof v.outputTruncated === 'boolean'
    && (v.exitCode === null || typeof v.exitCode === 'number' && Number.isSafeInteger(v.exitCode))
    && (v.durationMs === null || revision(v.durationMs))
}
export const submit = async (session: Session, room: string, source: string, language: Language, signal: AbortSignal) =>
  expectResponse(await session.request<unknown>(`/rooms/${room}/executions`, { method: 'POST', body: { source, language }, signal }),
    (v): v is Receipt => isRecord(v) && uuid(v.executionId) && status(v.status) && revision(v.stateRevision) && reason(v.failureReason))
export const history = async (session: Session, room: string, signal: AbortSignal) =>
  expectResponse(await session.request<unknown>(`/rooms/${room}/executions?page=0&size=20`, { signal }),
    (v): v is History => isRecord(v) && Array.isArray(v.items) && v.items.length <= 20 && v.items.every(item => summary(item) && item.roomId === room)
      && v.page === 0 && v.size === 20 && typeof v.hasNext === 'boolean')
export const get = async (session: Session, room: string, id: string, signal: AbortSignal) =>
  expectResponse(await session.request<unknown>(`/executions/${id}`, { signal }), (v): v is Detail => detail(v) && v.id === id && v.roomId === room)
