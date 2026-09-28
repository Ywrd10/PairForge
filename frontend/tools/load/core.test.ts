import { describe, expect, it, vi, afterEach } from 'vitest'
import { cleanupAll, config, Deliveries, distribution, elapsed, Journal, submitOnce, until } from './core.ts'
import local from './local.json'

afterEach(() => vi.useRealTimers())
describe('explicit workload bounds', () => {
  it('accepts the bounded local profile', () => expect(config(local)).toEqual(local))
  it.each(['maxConnections', 'maxJobs', 'maxDurationSeconds', 'deadlineMs', 'terminalDeadlineMs'])('requires %s', key => {
    expect(() => config({ ...local, [key]: undefined })).toThrow()
  })
  it('rejects jobs beyond the cap including warm-up and connections beyond configured maximum', () => {
    expect(() => config({ ...local, maxJobs: 24 })).toThrow()
    expect(() => config({ ...local, maxConnections: 5 })).toThrow()
    expect(() => config({ ...local, stages: [2, 2] })).toThrow()
  })
})
describe('timing and statistical reporting', () => {
  it('uses nearest-rank percentiles without changing the samples', () => {
    const samples = [100, 20, 1, 3]
    expect(distribution(samples)).toEqual({ count: 4, min: 1, p50: 3, p95: 100, p99: 100, max: 100, mean: 31 })
    expect(samples).toEqual([100, 20, 1, 3])
    expect(distribution(Array.from({ length: 100 }, (_, i) => i + 1)).p95).toBe(95)
  })
  it('does not fabricate empty timings and rejects invalid clocks', () => {
    expect(distribution([]).mean).toBeNull(); expect(distribution([]).p99).toBeNull()
    expect(() => distribution([NaN])).toThrow(); expect(() => distribution([-1])).toThrow()
    expect(elapsed(1.25, 2.5)).toBe(1.25); expect(() => elapsed(2, 1)).toThrow()
  })
})
describe('event correlation', () => {
  it('keeps an early terminal event until its receipt arrives and ignores duplicates/stale events', () => {
    const journal = new Journal(2, 'room')
    journal.observe('room', 'job', 0, 2, 'SUCCEEDED', 10)
    journal.observe('room', 'job', 0, 2, 'SUCCEEDED', 12)
    journal.observe('room', 'job', 0, 1, 'RUNNING', 13)
    expect(journal.terminal('job', 0)?.time).toBe(10)
    expect(journal.terminal('job', 1)).toBeUndefined()
    expect(journal.duplicates).toBe(2)
    expect(() => journal.observe('other-room', 'job', 0, 2, 'SUCCEEDED', 15)).toThrow()
  })
  it('bounds event storage', () => {
    const journal = new Journal(1, 'room')
    journal.observe('room', 'first', 0, 0, 'QUEUED', 1)
    expect(() => journal.observe('room', 'second', 0, 0, 'QUEUED', 2)).toThrow()
  })
  it('counts each observer once and validates generation/content', () => {
    const delivery = new Deliveries('id', 'generation', 'content', 10, 2)
    delivery.observe(0, 'unrelated', 'generation', 'content', 11)
    delivery.observe(0, 'id', 'generation', 'content', 12)
    delivery.observe(0, 'id', 'generation', 'content', 13)
    delivery.observe(1, 'id', 'generation', 'content', 15)
    expect([...delivery.times.values()]).toEqual([2, 5]); expect(delivery.duplicates).toBe(1)
    expect(() => delivery.observe(1, 'id', 'obsolete', 'content', 16)).toThrow()
  })
})
describe('deadlines and cleanup', () => {
  it('stops waiting at the observation deadline', async () => {
    vi.useFakeTimers()
    const waiting = until(() => false, 20, new AbortController().signal)
    const assertion = expect(waiting).rejects.toThrow('deadline')
    await vi.advanceTimersByTimeAsync(25); await assertion
  })
  it('honors an aborted maximum run duration', async () => {
    await expect(until(() => false, 100, AbortSignal.abort())).rejects.toThrow()
  })
  it('attempts all cleanup actions in reverse order even after a failure', async () => {
    const order: number[] = []
    await expect(cleanupAll([async () => { order.push(1) }, async () => { order.push(2); throw new Error('cleanup failure') }, async () => { order.push(3) }])).rejects.toThrow('cleanup')
    expect(order).toEqual([3, 2, 1])
  })
})
describe('submission uncertainty', () => {
  it.each(['network', 'malformed', '503'])('does not retry a %s outcome', async mode => {
    const send = vi.fn(async () => {
      if (mode === 'network') throw new Error('connection reset after commit')
      return new Response(mode === 'malformed' ? '{}' : '', { status: mode === '503' ? 503 : 202 })
    })
    expect((await submitOnce(send)).kind).toBe('uncertain'); expect(send).toHaveBeenCalledTimes(1)
  })
  it('distinguishes explicit rejection and accepted receipt', async () => {
    expect(await submitOnce(async () => new Response('', { status: 429 }))).toMatchObject({ kind: 'rejected', status: 429 })
    expect(await submitOnce(async () => Response.json({ executionId: '11111111-1111-1111-1111-111111111111' }, { status: 202 }))).toMatchObject({ kind: 'accepted' })
  })
})
