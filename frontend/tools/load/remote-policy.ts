import { config } from './core.ts'

export const approvedUsers = ['04ad007f-63ea-43fb-8d29-7cfaa9b089f1', '24ed65b0-9559-46d5-a2e2-0cbeec72b3b4']
export function remoteConfig(value: unknown) {
  const c = config(value)
  if (c.maxConnections !== 10 || c.maxJobs !== 30 || c.maxDurationSeconds !== 900 || c.deadlineMs !== 10000
    || c.terminalDeadlineMs !== 120000 || c.warmupSeconds !== 5 || c.measurementSeconds !== 20
    || c.repetitions !== 3 || c.jobsPerLanguage !== 4 || c.updateIntervalMs !== 500
    || c.executionBatchIntervalSeconds !== 60 || c.stages.join(',') !== '2,5,10') throw new Error('Remote workload differs from approved bounds')
  return c
}
export interface HostSample {
  role: 'App' | 'Worker'; cpuPercent: number; availableBytes: number; diskAvailableBytes: number; diskTotalBytes: number
  oomKills: number; cleanupFailures: number; healthy: boolean; ready: number; unacked: number
}
export function hostSample(value: unknown): HostSample {
  if (!value || typeof value !== 'object') throw new Error('Invalid host sample')
  const s = value as HostSample
  if (!['App', 'Worker'].includes(s.role) || typeof s.healthy !== 'boolean') throw new Error('Invalid host sample')
  for (const key of ['cpuPercent','availableBytes','diskAvailableBytes','diskTotalBytes','oomKills','cleanupFailures','ready','unacked'] as const)
    if (!Number.isFinite(s[key]) || s[key] < 0) throw new Error('Invalid host sample')
  if (s.cpuPercent > 100 || s.diskTotalBytes <= 0 || s.diskAvailableBytes > s.diskTotalBytes) throw new Error('Invalid host sample')
  return s
}
export class CapacityGuard {
  private states = new Map<string, { at: number; highSince?: number; oom: number; cleanup: number }>()
  observe(s: HostSample, at: number) {
    const prior = this.states.get(s.role)
    if (!s.healthy) throw new Error('Host readiness lost')
    if (s.availableBytes < 256 * 2**20) throw new Error('Available RAM guard reached')
    if (s.diskAvailableBytes < Math.max(2 * 2**30, s.diskTotalBytes * .1)) throw new Error('Free disk guard reached')
    if (prior && s.oomKills > prior.oom) throw new Error('OOM guard reached')
    if (prior && s.cleanupFailures > prior.cleanup) throw new Error('Cleanup failure guard reached')
    const highSince = s.cpuPercent > 85 ? prior?.highSince ?? at : undefined
    if (highSince !== undefined && at - highSince >= 60000) throw new Error('Sustained CPU guard reached; escalation paused')
    this.states.set(s.role, { at, highSince, oom: s.oomKills, cleanup: s.cleanupFailures })
  }
  fresh(at: number) {
    for (const role of ['App','Worker']) {
      const last = this.states.get(role)
      if (!last || at - last.at > 20000) throw new Error('Missing or stale capacity observation')
    }
  }
}
export function approvedIdentity(ids: string[]) {
  if (ids.length !== 2 || new Set(ids).size !== 2 || ids.some(id => !approvedUsers.includes(id))) throw new Error('Only the two approved accounts may benchmark')
}
export function privateSessions(input: string, now: number) {
  try {
    const value = JSON.parse(input) as { accessToken: string; expiresAt: string }[]
    if (!Array.isArray(value) || value.length !== 2 || value.some(s => !s || typeof s.accessToken !== 'string' || !s.accessToken
      || !Number.isFinite(Date.parse(s.expiresAt)) || Date.parse(s.expiresAt)-now < 700000)) throw new Error()
    return value
  } catch { throw new Error('Two fresh private sessions required; input is not logged') }
}
