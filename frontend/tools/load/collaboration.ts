import { randomUUID } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { Deliveries, cleanupAll, distribution, elapsed, until } from './core.ts'
import type { Config } from './core.ts'
import { Peer } from './transport.ts'

export async function collaboration(config: Config, room: string, tokens: string[], connections: number, signal: AbortSignal) {
  const peers: Peer[] = []
  const samples: number[] = []
  let active: Deliveries | undefined, updates = 0, deliveries = 0, duplicates = 0, attemptedUpdates = 0, measurementMs = 0
  let reconnectMs: number | null = null, restored = false
  const summarize = (passed: boolean) => ({ passed, connections, attemptedUpdates, updates, expectedDeliveries: attemptedUpdates * connections, deliveries, duplicates,
    measurementMs, deliveryLatencyMs: distribution(samples), rawDeliveryMs: samples, reconnectMs, restored,
    disconnects: peers.reduce((n, p) => n + p.disconnects, 0), errors: peers.reduce((n, p) => n + p.errors, 0) + (passed ? 0 : 1) })
  try {
    for (let i = 0; i < connections; i++) {
      const peer = new Peer(room, tokens[i % tokens.length], config.deadlineMs)
      peer.onDocument = event => { if (event.document) active?.observe(i, event.clientUpdateId, event.document.generationId, event.document.content, performance.now()) }
      peers.push(peer)
      await peer.open(signal)
    }
    const phase = async (seconds: number, measured: boolean) => {
      const start = performance.now(), stop = start + seconds * 1000
      const attempts = Math.ceil(seconds * 1000 / config.updateIntervalMs)
      for (let index = 0; index < attempts && performance.now() < stop; index++) {
        signal.throwIfAborted()
        const id = randomUUID(), content = `# ${id}\n`.padEnd(1024, 'x')
        active = new Deliveries(id, peers[0].document!.generationId, content, performance.now(), connections)
        if (measured) attemptedUpdates++
        peers[0].update(id, content)
        await until(() => { peers.forEach(p => p.assertHealthy()); return active!.times.size === connections }, config.deadlineMs, signal)
        if (measured) { updates++; deliveries += active.times.size; duplicates += active.duplicates; samples.push(...active.times.values()) }
        active = undefined
        const remaining = Math.min(stop, start + (index + 1) * config.updateIntervalMs) - performance.now()
        if (remaining > 0) await delay(remaining, undefined, { signal })
      }
      return elapsed(start, performance.now())
    }
    await phase(config.warmupSeconds, false)
    measurementMs = await phase(config.measurementSeconds, true)
    const old = peers.at(-1)!, expected = old.document!
    const began = performance.now()
    await old.close()
    // Allow the server's disconnect callback to release the per-user/IP slot.
    await delay(250, undefined, { signal })
    const reconnected = new Peer(room, tokens[(connections - 1) % tokens.length], config.deadlineMs)
    peers.push(reconnected)
    await reconnected.open(signal)
    restored = reconnected.document?.content === expected.content && reconnected.document?.version === expected.version
      && reconnected.document?.generationId === expected.generationId
    reconnectMs = elapsed(began, performance.now())
    if (!restored) throw new Error('Reconnect did not restore the shared document')
    return summarize(true)
  } catch {
    // Keep partial observations; missing deliveries remain missing rather than zero-latency samples.
    if (active) { deliveries += active.times.size; samples.push(...active.times.values()) }
    return summarize(false)
  } finally { await cleanupAll(peers.map(peer => () => peer.close())) }
}
