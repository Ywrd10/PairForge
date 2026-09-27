import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { execution } from './execution.ts'
import { config } from './core.ts'
import smoke from './smoke.json'

const boundary = vi.hoisted(() => ({ send: vi.fn<() => Promise<Response>>(), closed: [] as number[] }))
vi.mock('./transport.ts', () => ({
  request: boundary.send,
  json: vi.fn(() => { throw new Error('No terminal result should be fetched') }),
  Peer: class {
    private index: number
    constructor() { this.index = boundary.closed.length; boundary.closed.push(0) }
    async open() {}
    assertHealthy() {}
    async close() { boundary.closed[this.index]++ }
  },
}))
beforeEach(() => { boundary.send.mockReset(); boundary.closed.length = 0 })
afterEach(() => vi.useRealTimers())

it('stops the workload on uncertain submission, reports it, and closes both peers', async () => {
  boundary.send.mockRejectedValue(new Error('Disconnected after possible commit'))
  const result = await execution(config(smoke), 'room', ['one', 'two'], 'JAVA', new AbortController().signal)
  expect(result).toMatchObject({ passed: false, submitted: 1, uncertain: 1, accepted: 0 })
  expect(boundary.send).toHaveBeenCalledTimes(1)
  expect(boundary.closed).toEqual([1, 1])
})
it('records an observation timeout without resubmitting an accepted job and cleans up', async () => {
  vi.useFakeTimers()
  boundary.send.mockResolvedValue(Response.json({ executionId: '11111111-1111-1111-1111-111111111111' }, { status: 202 }))
  const run = execution(config({ ...smoke, terminalDeadlineMs: 1000 }), 'room', ['one', 'two'], 'PYTHON', new AbortController().signal)
  await vi.advanceTimersByTimeAsync(1100)
  expect(await run).toMatchObject({ passed: false, submitted: 1, accepted: 1, observationTimeouts: 1 })
  expect(boundary.send).toHaveBeenCalledTimes(1)
  expect(boundary.closed).toEqual([1, 1])
})
