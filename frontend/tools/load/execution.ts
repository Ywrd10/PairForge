import { cleanupAll, distribution, elapsed, Journal, submitOnce, until } from './core.ts'
import type { Config } from './core.ts'
import { Peer, json, request } from './transport.ts'

export async function execution(config: Config, room: string, tokens: string[], language: 'JAVA' | 'PYTHON', signal: AbortSignal) {
  const peers: Peer[] = [], journal = new Journal(config.maxJobs * 2, room)
  const jobs: { id: string; submitted: number; acceptanceMs: number; endToEndMs: number[]; status: string; warmup: boolean }[] = []
  const rejected: number[] = []
  let submitted = 0, acceptedCount = 0, uncertain = 0, observationTimeouts = 0, measurementMs = 0
  const summarize = (passed: boolean) => {
    const measured = jobs.filter(j => !j.warmup)
    return { passed, language, submitted, accepted: acceptedCount, rejected, uncertain, observationTimeouts,
      failures: jobs.filter(j => j.status === 'FAILED').length, timeouts: jobs.filter(j => j.status === 'TIMED_OUT').length,
      measurementMs, throughputJobsPerSecond: measurementMs > 0 ? measured.length * 1000 / measurementMs : null,
      acceptanceMs: distribution(measured.map(j => j.acceptanceMs)),
      endToEndMs: distribution(measured.flatMap(j => j.endToEndMs)), duplicateEvents: journal.duplicates, jobs }
  }
  const source = language === 'JAVA' ? 'public class Main { public static void main(String[] a) { System.out.println("PairForge load OK"); } }' : 'print("PairForge load OK")'
  try {
    for (let i = 0; i < 2; i++) {
      const peer = new Peer(room, tokens[i], config.deadlineMs)
      peer.onExecution = e => journal.observe(e.roomId, e.executionId, i, e.stateRevision, e.status, performance.now())
      peers.push(peer); await peer.open(signal)
    }
    const run = async (count: number, warmup: boolean) => {
      const start = performance.now(), accepted: { id: string; submitted: number; acceptanceMs: number }[] = []
      for (let index = 0; index < count; index++) {
        signal.throwIfAborted()
        const began = performance.now(); submitted++
        const receipt = await submitOnce(() => request(`/rooms/${room}/executions`, tokens[index % 2], { language, source }, signal, config.deadlineMs))
        if (receipt.kind === 'uncertain') { uncertain++; throw new Error('Execution submission uncertain; stopped without retry') }
        if (receipt.kind === 'rejected') { rejected.push(receipt.status); continue }
        acceptedCount++
        accepted.push({ id: receipt.id, submitted: began, acceptanceMs: receipt.latencyMs })
      }
      for (const job of accepted) {
        try {
          await until(() => { peers.forEach(p => p.assertHealthy()); return !!journal.terminal(job.id, 0) && !!journal.terminal(job.id, 1) },
            Math.max(1, config.terminalDeadlineMs - elapsed(job.submitted, performance.now())), signal)
        } catch (error) {
          if (error instanceof Error && error.message === 'Observation deadline exceeded') observationTimeouts++
          throw new Error('Terminal notification deadline/transport failure')
        }
        const events = [journal.terminal(job.id, 0)!, journal.terminal(job.id, 1)!]
        const result = await json<{ status: string; stdout: string; exitCode: number }>(`/executions/${job.id}`, tokens[0], undefined, signal, config.deadlineMs)
        jobs.push({ ...job, endToEndMs: events.map(e => elapsed(job.submitted, e.time)), status: result.status, warmup })
        if (events.some(e => e.status !== result.status) || result.status !== 'SUCCEEDED' || result.exitCode !== 0 || result.stdout !== 'PairForge load OK\n') throw new Error('Execution result verification failed')
      }
      return elapsed(start, performance.now())
    }
    await run(1, true)
    measurementMs = await run(config.jobsPerLanguage, false)
    if (rejected.length) throw new Error('Bounded baseline unexpectedly rejected requests')
    return summarize(true)
  } catch {
    return summarize(false)
  } finally { await cleanupAll(peers.map(peer => () => peer.close())) }
}
