import { spawn, execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { resolve } from 'node:path'
import { createInterface } from 'node:readline'
import { CapacityGuard, hostSample } from './remote-policy.ts'

const exec = promisify(execFile)
const root = resolve(import.meta.dirname, '../../..')
function args(role: 'App' | 'Worker', command: string) {
  return ['-NoProfile','-File',resolve(root,'scripts/invoke-deployment-ssh.ps1'),'-Role',role,'-Command',command]
}
export async function remoteCommand(pwsh: string, role: 'App' | 'Worker', mode: 'timings' | 'cleanup', room?: string) {
  if (mode === 'timings' && !/^[0-9a-f-]{36}$/.test(room ?? '')) throw new Error('Invalid benchmark room')
  try {
    const { stdout } = await exec(pwsh, args(role, `sudo timeout 45 bash /home/ubuntu/m16-observe.sh ${mode} ${role}${room ? ' '+room : ''}`),
      { timeout: 60000, maxBuffer: 128 * 1024, windowsHide: true })
    const lines = stdout.trim().split(/\r?\n/).filter(l=>l.startsWith('[')||l.startsWith('{'))
    if (lines.length !== 1) throw new Error('Missing remote JSON')
    return JSON.parse(lines[0]) as unknown
  } catch { throw new Error('Private benchmark inspection failed') }
}
export function observeHosts(pwsh: string, abort: AbortController) {
  const guard = new CapacityGuard(), samples: unknown[] = [], failures: string[] = []
  let stopped = false, phase = 'startup'
  const fail = (reason: string) => { if (!stopped && !abort.signal.aborted) { failures.push(reason); abort.abort() } }
  const children = (['App','Worker'] as const).map(role => {
    const child = spawn(pwsh, args(role, `sudo timeout 920 bash /home/ubuntu/m16-observe.sh observe ${role}`), { windowsHide: true, stdio: ['ignore','pipe','pipe'] })
    // Never publish arbitrary SSH diagnostics; they can contain private paths.
    child.stderr.resume()
    const lines = createInterface({ input: child.stdout })
    lines.on('line', line => {
      if (!line.startsWith('{')) return
      try {
        const sample = hostSample(JSON.parse(line))
        if (sample.role !== role || samples.length >= 400) throw new Error('Invalid/bounded capacity stream')
        samples.push({ ...sample, observedAt: new Date().toISOString(), phase })
        guard.observe(sample, performance.now())
      } catch (e) { fail(e instanceof Error ? e.message : 'Invalid capacity stream') }
    })
    child.on('error',()=>fail('Host observer could not start'))
    child.on('exit',()=>fail('Host observer ended before workload completion'))
    return { child, lines }
  })
  let monitoring = false
  const watchdog = setInterval(()=> { if (monitoring) try { guard.fresh(performance.now()) } catch { fail('Missing or stale capacity observation') } },500)
  return { samples, failures, guard,
    phase(value: string) { phase=value },
    startGuard() { guard.fresh(performance.now()); monitoring=true },
    async stop() {
      stopped=true;clearInterval(watchdog)
      for (const {child,lines} of children) {
        lines.close()
        if (child.pid && child.exitCode === null) {
          if (process.platform === 'win32') await exec('taskkill',['/PID',String(child.pid),'/T','/F'],{windowsHide:true,timeout:10000}).catch(()=>{child.kill()})
          else child.kill('SIGTERM')
        }
      }
    },
  }
}
