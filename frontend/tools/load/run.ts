import { randomBytes, createHash } from 'node:crypto'
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { startFixture } from '../../e2e/setup.ts'
import { config, distribution } from './core.ts'
import { collaboration } from './collaboration.ts'
import { execution } from './execution.ts'
import { json, request, base } from './transport.ts'
import { environment, sampler, command } from './report.ts'

const [configPath] = process.argv.slice(2)
if (!configPath || process.argv.length !== 3) throw new Error('Usage: node tools/load/run.ts <explicit-config.json>')
const settings = config(JSON.parse(await readFile(resolve(configPath), 'utf8')))
const directory = resolve(import.meta.dirname, '../../../.tmp/load', new Date().toISOString().replace(/[:.]/g, '-') + '-' + randomBytes(3).toString('hex'))
await mkdir(directory, { recursive: true })
const digest = createHash('sha256')
for (const name of (await readdir(import.meta.dirname)).filter(n => n.endsWith('.ts')).sort()) digest.update(name).update(await readFile(resolve(import.meta.dirname, name)))
const report: Record<string, unknown> = { started: new Date().toISOString(), settings, harnessSha256: digest.digest('hex'),
  environment: await environment(), collaboration: [], execution: [], passed: false, cleanupPassed: false }
const collab = report.collaboration as unknown[], executions = report.execution as unknown[]
const abort = new AbortController(), deadline = setTimeout(() => abort.abort(), settings.maxDurationSeconds * 1000)
const interrupt = () => abort.abort()
process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt)
let fixture: Awaited<ReturnType<typeof startFixture>> | undefined, sampling: ReturnType<typeof sampler> | undefined
let current = 'fixture startup'
try {
  fixture = await startFixture(true, abort.signal, Date.now() + settings.maxDurationSeconds * 1000)
  abort.signal.throwIfAborted()
  report.images = fixture.images
  sampling = sampler(directory)
  const tokens: string[] = []
  for (let i = 0; i < 2; i++) {
    const body = { email: `load-${randomBytes(8).toString('hex')}@example.test`, password: randomBytes(24).toString('hex') }
    await json('/auth/register', undefined, body, abort.signal, settings.deadlineMs)
    const session = await json<{ accessToken: string }>('/auth/login', undefined, body, abort.signal, settings.deadlineMs)
    tokens.push(session.accessToken)
  }
  const room = await json<{ room: { id: string }; invitationToken: string }>('/rooms', tokens[0], { name: 'Local load fixture', language: 'PYTHON' }, abort.signal, settings.deadlineMs)
  const denied = await request(`/rooms/${room.room.id}`, tokens[1], undefined, abort.signal, settings.deadlineMs)
  if (denied.status !== 404) throw new Error('Nonmember room access was not rejected')
  report.authorizationCheck = 'Nonmember room read rejected with 404 before invitation join'
  await json(`/rooms/${room.room.id}/join`, tokens[1], { invitationToken: room.invitationToken }, abort.signal, settings.deadlineMs)
  for (let repetition = 1; repetition <= settings.repetitions; repetition++) {
    for (const stage of settings.stages) {
      current = `collaboration-${stage}-repeat-${repetition}`; sampling.phase(current); console.log(current)
      const result = await collaboration(settings, room.room.id, tokens, stage, abort.signal)
      collab.push({ repetition, ...result })
      if (!result.passed) throw new Error('Collaboration baseline failed')
      await writeFile(resolve(directory, 'report.json'), JSON.stringify(report, null, 2))
      await delay(500, undefined, { signal: abort.signal })
    }
  }
  let lastBatch = -Infinity
  for (let repetition = 1; repetition <= settings.repetitions; repetition++) {
    for (const language of ['JAVA', 'PYTHON'] as const) {
      const pause = settings.executionBatchIntervalSeconds * 1000 - (performance.now() - lastBatch)
      if (pause > 0) { sampling.phase('execution-rate-limit-cooldown'); await delay(pause, undefined, { signal: abort.signal }) }
      current = `execution-${language}-repeat-${repetition}`; sampling.phase(current); console.log(current); lastBatch = performance.now()
      const result = await execution(settings, room.room.id, tokens, language, abort.signal)
      if (!result.passed) {
        executions.push({ repetition, ...result, jobs: result.jobs.map(job => ({ warmup: job.warmup, acceptanceMs: job.acceptanceMs, endToEndMs: job.endToEndMs, status: job.status })) })
        throw new Error('Execution baseline failed')
      }
      const durable = fixture.timings()
      const timings = result.jobs.map(job => {
        const row = durable.find(row => row.id === job.id)
        if (!row || row.queue_ms === null || row.duration_ms === null || row.status !== job.status) throw new Error('Missing durable execution measurements')
        return { warmup: job.warmup, acceptanceMs: job.acceptanceMs, endToEndMs: job.endToEndMs, status: job.status, queueMs: row.queue_ms, durationMs: row.duration_ms }
      })
      const measured = timings.filter(j => !j.warmup)
      // UUIDs and source/output stay out of the report. Durable timings are correlated in memory.
      executions.push({ repetition, ...result, jobs: timings, queueWaitMs: distribution(measured.map(j => j.queueMs)), durationMs: distribution(measured.map(j => j.durationMs)) })
      report.queue = fixture.queue()
      await writeFile(resolve(directory, 'report.json'), JSON.stringify(report, null, 2))
    }
  }
  current = 'final readiness and cleanup'
  for (const port of [18082, 18083]) {
    const response = await fetch(`${base.replace('18080', String(port))}/actuator/health/readiness`, { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(settings.deadlineMs)]) })
    if (!response.ok) throw new Error('Final readiness failed')
  }
  // Wait for post-commit cleanup before asserting zero owned sandbox resources.
  await delay(1500, undefined, { signal: abort.signal })
  if (await command('docker', ['ps', '-aq', '--filter', `label=io.pairforge.sandbox=${fixture.namespace}`])) throw new Error('Sandbox containers survived completed work')
  if ((await readdir(resolve(fixture.workspace, fixture.namespace))).length) throw new Error('Sandbox job workspaces survived completed work')
  report.queue = fixture.queue()
  const queues = String(report.queue).split('\n').map(line => line.split('\t')).filter(row => row[0].startsWith('execution.'))
  if (queues.length !== 2 || queues.some(row => row[1] !== '0' || row[2]?.trim() !== '0')) throw new Error('Final execution queues did not drain')
  report.passed = true
} catch (error) {
  const safe = error instanceof Error && /^(Sandbox |Final |Missing durable |Execution baseline |Collaboration baseline |Test API |Test sandbox |Test worker |Test PostgreSQL |Docker |Build the |Load HTTP |Nonmember )/.test(error.message)
  report.failure = { phase: current, reason: abort.signal.aborted ? 'Run interrupted or maximum duration exceeded' : safe ? error.message : 'Scenario/prerequisite failed; no uncertain submission was retried' }
  process.exitCode = 1
} finally {
  clearTimeout(deadline)
  if (sampling) {
    await sampling.stop(); report.resources = sampling.samples; report.resourceErrors = sampling.failures
    if (sampling.failures.length) { report.passed = false; process.exitCode = 1 }
  }
  try { if (fixture) { await fixture.cleanup(); report.cleanupPassed = true } }
  catch { report.cleanupPassed = false; report.passed = false; process.exitCode = 1 }
  report.finished = new Date().toISOString()
  await writeFile(resolve(directory, 'report.json'), JSON.stringify(report, null, 2))
  process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt)
  console.log(`Load report: ${resolve(directory, 'report.json')}; passed=${report.passed}; cleanup=${report.cleanupPassed}`)
}
