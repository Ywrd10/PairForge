import { beforeEach, expect, it, vi } from 'vitest'
import type { Session } from '../auth/session'
import { ApiError } from '../api/client'
import type { Detail, ExecutionEvent } from '../api/executions'
import { ExecutionClient } from './client'

const room = '11111111-1111-4111-8111-111111111111'
const id = '22222222-2222-4222-8222-222222222222'
const other = '33333333-3333-4333-8333-333333333333'
const detail = (revision = 2, executionId = id): Detail => ({ id: executionId, roomId: room, language: 'PYTHON', status: revision === 2 ? 'SUCCEEDED' : 'RUNNING',
  stateRevision: revision, createdAt: '2026-09-22T00:00:00Z', failureReason: null, source: 'print(1)', stdout: revision === 2 ? '1\n' : '', stderr: '',
  exitCode: revision === 2 ? 0 : null, durationMs: revision === 2 ? 10 : null, outputTruncated: false })
const event = (revision: number, executionId = id): ExecutionEvent => ({ schemaVersion: 1, executionId, roomId: room,
  status: revision === 2 ? 'SUCCEEDED' : 'RUNNING', stateRevision: revision })
let client: ExecutionClient
let request: ReturnType<typeof vi.fn>
let current: Detail
beforeEach(() => {
  vi.useFakeTimers(); current = detail()
  request = vi.fn(async (url: string, options?: { method?: string }) => {
    if (options?.method === 'POST') return { executionId: id, status: 'QUEUED', stateRevision: 0, failureReason: null }
    if (url.includes('?')) return { items: [current], page: 0, size: 20, hasNext: false }
    return current
  })
  client = new ExecutionClient({ request } as unknown as Session, room); client.start()
})
it('submits the visible snapshot once and never waits for editor debounce', async () => {
  let resolve!: (v: unknown) => void
  request.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const run = client.run('pending local source', 'JAVA'); void client.run('duplicate click', 'PYTHON')
  expect(request).toHaveBeenCalledTimes(1)
  expect(request.mock.calls[0][1]).toMatchObject({ method: 'POST', body: { source: 'pending local source', language: 'JAVA' } })
  resolve({ executionId: id, status: 'QUEUED', stateRevision: 0, failureReason: null }); await run
  await vi.advanceTimersByTimeAsync(200)
  expect(client.getSnapshot().detail?.stdout).toBe('1\n'); client.stop()
})
it('coalesces events, ignores duplicates and preserves terminal state against stale REST', async () => {
  await client.refresh(); request.mockClear()
  client.event(event(2)); client.event(event(2)); client.event(event(1))
  current = detail(1)
  await vi.advanceTimersByTimeAsync(150)
  expect(request).toHaveBeenCalledTimes(2)
  expect(client.getSnapshot().detail?.status).toBe('SUCCEEDED')
  expect(client.getSnapshot().items[0].status).toBe('SUCCEEDED'); client.stop()
})
it('uncertain dispatch retains its ID and manual recovery never repeats POST', async () => {
  request.mockRejectedValueOnce(new ApiError('Uncertain', 503, {}, undefined, undefined, id, true))
  await client.run('print(1)', 'PYTHON'); await vi.advanceTimersByTimeAsync(200)
  expect(client.getSnapshot().selected).toBe(id)
  expect(client.getSnapshot().notice).toContain('No automatic resubmission')
  await client.refresh()
  expect(request.mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(1); client.stop()
})
it('lost HTTP response recovers recent executions without automatic resubmission', async () => {
  request.mockRejectedValueOnce(new ApiError('Network loss'))
  await client.run('print(1)', 'PYTHON'); await vi.advanceTimersByTimeAsync(200)
  expect(client.getSnapshot().selected).toBe(id)
  expect(client.getSnapshot().detail?.status).toBe('SUCCEEDED')
  expect(request.mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(1); client.stop()
})
it('an unreadable accepted response is uncertain and never automatically resubmitted', async () => {
  request.mockRejectedValueOnce(new ApiError('The server returned an unreadable response.', 202))
  await client.run('print(1)', 'PYTHON'); await vi.advanceTimersByTimeAsync(200)
  expect(client.getSnapshot().notice).toContain('Submission outcome may be uncertain')
  expect(client.getSnapshot().notice).toContain('No automatic resubmission')
  expect(client.getSnapshot().detail?.status).toBe('SUCCEEDED')
  expect(request.mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(1); client.stop()
})
it('navigation cancels and ignores stale responses from a prior lifecycle', async () => {
  let resolve!: (v: unknown) => void
  request.mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const pending = client.refresh(); client.stop(); client.start()
  resolve({ items: [detail()], page: 0, size: 20, hasNext: false }); await pending
  expect(client.getSnapshot().items).toEqual([]); client.stop()
})
it('older completion cannot replace the user-selected execution', async () => {
  await client.refresh()
  current = detail(1, other); client.select(other); await vi.advanceTimersByTimeAsync(150)
  client.event(event(2)); await vi.advanceTimersByTimeAsync(150)
  expect(client.getSnapshot().selected).toBe(other)
  expect(client.getSnapshot().detail?.id).toBe(other); client.stop()
})
it('missed notification shows a bounded hint without polling and Refresh Status recovers', async () => {
  current = detail(1); await client.refresh(); request.mockClear()
  await vi.advanceTimersByTimeAsync(60000)
  expect(client.getSnapshot().delayed).toBe(true); expect(request).not.toHaveBeenCalled()
  current = detail(); await client.refresh()
  expect(client.getSnapshot().delayed).toBe(false); expect(client.getSnapshot().detail?.status).toBe('SUCCEEDED'); client.stop()
})
it('invalid or oversized visible source never dispatches', async () => {
  for (const source of ['\0', '\ud800', 'x'.repeat(65537)]) await client.run(source, 'PYTHON')
  expect(request).not.toHaveBeenCalled(); client.stop()
})
it('selection changes restart the delayed-status hint and successful refresh clears its error', async () => {
  current = detail(1); await client.refresh()
  current = detail(1, other); client.select(other); await vi.advanceTimersByTimeAsync(150)
  await vi.advanceTimersByTimeAsync(30000); expect(client.getSnapshot().delayed).toBe(true)
  request.mockRejectedValueOnce(new ApiError('Unavailable', 503)); await client.refresh()
  expect(client.getSnapshot().refreshError).toContain('could not be refreshed')
  current = detail(2, other); await client.refresh()
  expect(client.getSnapshot().refreshError).toBe(''); expect(client.getSnapshot().delayed).toBe(false); client.stop()
})
