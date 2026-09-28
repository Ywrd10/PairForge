import { expect, it, vi } from 'vitest'
import { Peer } from './transport.ts'

const control = vi.hoisted(() => ({ closed: 0 }))
vi.mock('@stomp/stompjs', () => ({ Client: class {
  onConnect = () => {}
  connectHeaders = {}
  activate() { this.onConnect() }
  subscribe() { throw new Error('Socket closed during subscription') }
  async deactivate() { control.closed++ }
} }))

it('contains subscription exceptions so the caller can run cleanup', async () => {
  control.closed = 0
  const peer = new Peer('room', 'private-token', 100)
  try {
    await expect(peer.open(new AbortController().signal)).rejects.toThrow('protocol/transport error')
    expect(peer.errors).toBe(1)
  } finally { await peer.close() }
  expect(control.closed).toBe(1)
})
