import { cpus, freemem, totalmem, platform, release } from 'node:os'
import { statfs } from 'node:fs/promises'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { setTimeout as delay } from 'node:timers/promises'

const exec = promisify(execFile)
export async function command(command: string, args: string[]) {
  try { return (await exec(command, args, { timeout: 15000, maxBuffer: 1024 * 1024, windowsHide: true })).stdout.trim() }
  catch { throw new Error(`Load inspection failed: ${command}`) }
}
export async function environment() {
  const docker = JSON.parse(await command('docker', ['info', '--format', '{{json .}}'])) as Record<string, unknown>
  return { os: platform(), release: release(), cpu: cpus()[0]?.model, logicalCpus: cpus().length,
    hostMemoryBytes: totalmem(), node: process.version, java: await command('java', ['--version']),
    docker: { version: docker.ServerVersion, kernel: docker.KernelVersion, cpus: docker.NCPU, memoryBytes: docker.MemTotal },
    baseCommit: await command('git', ['rev-parse', 'HEAD']), workingTreeDirty: !!await command('git', ['status', '--porcelain']) }
}
export interface Sample { time: string; phase: string; hostFreeBytes: number; generatorRssBytes: number; diskAvailableBytes: number; api: string[]; worker: string[] }
export function sampler(directory: string) {
  const samples: Sample[] = [], failures: string[] = []
  let phase = 'setup', stopped = false
  const stop = new AbortController()
  const metrics = async (port: number) => {
    const response = await fetch(`http://127.0.0.1:${port}/actuator/prometheus`, { signal: AbortSignal.any([stop.signal, AbortSignal.timeout(2000)]) })
    if (!response.ok) throw new Error('Management scrape failed')
    return (await response.text()).split('\n').filter(line => /^(process_cpu_usage|system_cpu_usage|jvm_memory_used_bytes|jvm_threads_live_threads|pairforge_websocket_|pairforge_execution_)/.test(line))
  }
  const task = (async () => {
    while (!stopped) {
      try {
        const [api, worker, disk] = await Promise.all([metrics(18082), metrics(18083), statfs(directory)])
        samples.push({ time: new Date().toISOString(), phase, hostFreeBytes: freemem(), generatorRssBytes: process.memoryUsage().rss,
          diskAvailableBytes: disk.bavail * disk.bsize, api, worker })
      } catch { if (!stopped) failures.push('Capacity/management sample failed') }
      if (samples.length + failures.length >= 600) { failures.push('Sample bound reached'); break }
      try { await delay(2000, undefined, { signal: stop.signal }) }
      catch { /* Shutdown only. */ }
    }
  })()
  return { samples, failures, phase: (value: string) => { phase = value }, stop: async () => { stopped = true; stop.abort(); await task } }
}
