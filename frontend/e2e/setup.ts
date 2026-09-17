import { execFileSync, spawn } from 'node:child_process'
import type { ChildProcess } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync, readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { createServer } from 'node:net'

const root = resolve(import.meta.dirname, '../..')
function docker(args: string[]) {
  try { return execFileSync('docker', args, { encoding: 'utf8', timeout: 180_000, stdio: ['ignore', 'pipe', 'pipe'] }).trim() }
  catch { throw new Error(`Docker ${args[0]} failed; check the daemon and required images. Command arguments are redacted.`) }
}

export default async function setup() {
  docker(['info', '--format', '{{.ServerVersion}}']) // Required, never skip missing Docker.
  const target = resolve(root, 'backend/target')
  const jar = existsSync(target) && readdirSync(target).find(name => name.endsWith('.jar'))
  if (!jar) throw new Error('Build the backend jar before browser tests (Maven package).')
  // Refuse to accidentally use a pre-existing developer API on the test port.
  await new Promise<void>((done, reject) => {
    const probe = createServer()
    probe.once('error', reject)
    probe.listen(18080, '127.0.0.1', () => probe.close(error => error ? reject(error) : done()))
  })
  const suffix = randomBytes(6).toString('hex')
  const password = randomBytes(24).toString('hex')
  const names: string[] = []
  let api: ChildProcess | undefined
  let launchError = false
  async function cleanup() {
    const failures: string[] = []
    if (api && api.exitCode === null) {
      const child = api
      const exited = new Promise<void>(done => child.once('exit', () => done()))
      try {
        if (process.platform === 'win32') {
          // Oracle's PATH shim can spawn a second java.exe; stop our entire owned tree.
          execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true })
        } else child.kill()
      } catch { if (child.exitCode === null) failures.push(`API process ${child.pid}`) }
      await Promise.race([exited, new Promise<void>(done => setTimeout(done, 5000).unref())])
      if (child.exitCode === null) child.kill('SIGKILL')
    }
    for (const name of names.reverse()) {
      try { docker(['rm', '-f', '-v', name]) } catch { failures.push(name) }
    }
    if (failures.length) throw new Error(`Test resource cleanup failed: ${failures.join(', ')}`)
  }
  function container(service: string, image: string, port: number, env: string[] = []) {
    const name = `pairforge-e2e-${suffix}-${service}`
    names.push(name)
    docker(['run', '-d', '--name', name, '-p', `127.0.0.1::${port}`, ...env.flatMap(value => ['-e', value]), image])
    return docker(['port', name, `${port}/tcp`]).split(':').at(-1)!
  }
  try {
    const pg = container('postgres', 'postgres:17.11-bookworm', 5432,
      ['POSTGRES_DB=pairforge', 'POSTGRES_USER=pairforge', `POSTGRES_PASSWORD=${password}`])
    const redis = container('redis', 'redis:7.4.11-bookworm', 6379)
    process.env.PAIRFORGE_E2E_REDIS_CONTAINER = names[1]
    const rabbit = container('rabbit', 'rabbitmq:4.1.8', 5672,
      ['RABBITMQ_DEFAULT_USER=pairforge', `RABBITMQ_DEFAULT_PASS=${password}`])
    // Wait for PostgreSQL before Flyway starts. Redis/Rabbit readiness is checked through the API.
    const deadline = Date.now() + 60_000
    while (true) {
      try { docker(['exec', names[0], 'pg_isready', '-h', '127.0.0.1', '-U', 'pairforge']); break }
      catch { if (Date.now() > deadline) throw new Error('Test PostgreSQL did not become ready') }
      await new Promise(done => setTimeout(done, 500))
    }
    api = spawn('java', ['-jar', resolve(target, jar)], {
      cwd: root, windowsHide: true, stdio: 'ignore',
      env: { ...process.env, SPRING_PROFILES_ACTIVE: '', API_PORT: '18080',
        DATABASE_URL: `jdbc:postgresql://127.0.0.1:${pg}/pairforge`, DATABASE_USER: 'pairforge', DATABASE_PASSWORD: password,
        REDIS_HOST: '127.0.0.1', REDIS_PORT: redis, RABBITMQ_HOST: '127.0.0.1', RABBITMQ_PORT: rabbit,
        RABBITMQ_USER: 'pairforge', RABBITMQ_PASSWORD: password, JWT_KEY_HEX: randomBytes(32).toString('hex'),
        PAIRFORGE_AUTH_ALLOWEDORIGINS: 'http://127.0.0.1:15173', PAIRFORGE_AUTH_BCRYPTCOST: '4',
        PAIRFORGE_AUTH_REGISTRATIONIPLIMIT: '100', PAIRFORGE_AUTH_LOGINIPLIMIT: '100',
      },
    })
    api.on('error', () => { launchError = true })
    const readyDeadline = Date.now() + 90_000
    while (true) {
      if (launchError || api.exitCode !== null) throw new Error('Test API failed to start')
      try {
        const health = await fetch('http://127.0.0.1:18080/actuator/health/readiness', { signal: AbortSignal.timeout(2000) })
        if (health.ok) break
      } catch { /* Startup connection failures are retried until the bounded deadline. */ }
      if (Date.now() > readyDeadline) throw new Error('Test API readiness timed out')
      await new Promise(done => setTimeout(done, 500))
    }
    return cleanup
  } catch (error) {
    await cleanup()
    throw error
  }
}
