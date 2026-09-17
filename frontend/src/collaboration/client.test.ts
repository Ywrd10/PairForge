import { beforeEach, expect, it, vi } from 'vitest'
import type { Session } from '../auth/session'
import type { IMessage } from '@stomp/stompjs'
import { CollaborationClient } from './client'

const harness = vi.hoisted(() => ({ current: null as FakeTransport | null }))
interface FakeTransport {
  connected: boolean; connectHeaders: object; beforeConnect?: () => void; onConnect?: () => void
  onWebSocketClose?: () => void; onStompError?: () => void; receive?: (message: IMessage) => void
  publish: ReturnType<typeof vi.fn>; deactivate: ReturnType<typeof vi.fn>
}
vi.mock('@stomp/stompjs', () => ({ Client: class {
  connected = true
  connectHeaders = {}
  beforeConnect?: () => void
  onConnect?: () => void
  publish = vi.fn()
  deactivate = vi.fn(async () => {})
  constructor() { harness.current = this }
  activate() { this.beforeConnect?.(); this.onConnect?.() }
  subscribe(_destination: string, receive: (message: IMessage) => void) { harness.current!.receive = receive }
} }))
const room = '11111111-1111-4111-8111-111111111111'
const generation = '22222222-2222-4222-8222-222222222222'
let client: CollaborationClient
let transport: FakeTransport
function event(type = 'SNAPSHOT', version = 0, content = 'initial', clientUpdateId: string | null = null, gen = generation) {
  transport.receive!({ body: JSON.stringify({ type, roomId: room, document: { generationId: gen, version, content, language: 'JAVA' }, clientUpdateId, code: null }) } as IMessage)
}
beforeEach(() => {
  vi.useFakeTimers()
  const session = { collaborationHeaders: () => ({ Authorization: 'Bearer test-only' }) } as Session
  client = new CollaborationClient(session, room, 'starter', 'JAVA')
  client.start(); transport = harness.current!
})
it('subscribes before requesting a snapshot and keeps credentials out of subsequent transport state', () => {
  expect(transport.connectHeaders).toEqual({})
  expect(transport.publish).toHaveBeenCalledWith(expect.objectContaining({ destination: `/app/rooms/${room}/snapshot` }))
  expect(client.getSnapshot().ready).toBe(false)
  event(); expect(client.getSnapshot()).toMatchObject({ ready: true, connected: true, content: 'initial' })
  client.stop()
})
it('debounces and coalesces edits with only one update in flight', () => {
  event(); client.edit('one', 'JAVA'); client.edit('two', 'PYTHON'); vi.advanceTimersByTime(299)
  expect(transport.publish).toHaveBeenCalledTimes(1)
  vi.advanceTimersByTime(1)
  const sent = JSON.parse(transport.publish.mock.lastCall![0].body)
  expect(sent).toMatchObject({ content: 'two', language: 'PYTHON', sequence: 1 })
  client.edit('three', 'JAVA'); vi.advanceTimersByTime(300)
  expect(transport.publish).toHaveBeenCalledTimes(2)
  event('UPDATED', 1, 'two', sent.clientUpdateId)
  expect(client.getSnapshot().content).toBe('three')
  vi.advanceTimersByTime(300)
  expect(JSON.parse(transport.publish.mock.lastCall![0].body).content).toBe('three')
  client.stop()
})
it('orders racing snapshots and remote events without echoing them', () => {
  event('UPDATED', 2, 'newer'); event('SNAPSHOT', 1, 'older'); event('UPDATED', 1, 'old again')
  expect(client.getSnapshot().content).toBe('newer')
  vi.advanceTimersByTime(1000); expect(transport.publish).toHaveBeenCalledTimes(1)
  client.stop()
})
it('resets generations explicitly and ignores delayed old-generation traffic', () => {
  event(); client.edit('local', 'JAVA')
  event('DOCUMENT_RESET', 0, 'reset', null, '33333333-3333-4333-8333-333333333333')
  event('UPDATED', 999, 'obsolete')
  expect(client.getSnapshot().content).toBe('reset')
  expect(client.getSnapshot().notice).toContain('reset')
  vi.advanceTimersByTime(1000); expect(transport.publish).toHaveBeenCalledTimes(1)
  client.stop()
})
it('retains disconnected edits and never replays or reconnects automatically', () => {
  event(); client.edit('keep me', 'JAVA'); transport.onWebSocketClose!()
  vi.advanceTimersByTime(20000)
  expect(client.getSnapshot()).toMatchObject({ connected: false, content: 'keep me', pending: true })
  expect(transport.publish).toHaveBeenCalledTimes(1)
  expect(transport.deactivate).toHaveBeenCalled()
})
it('does not replay a newer local draft when its in-flight update receives a reset', () => {
  event(); client.edit('in flight', 'JAVA'); vi.advanceTimersByTime(300)
  const sent = JSON.parse(transport.publish.mock.lastCall![0].body)
  client.edit('newer local draft', 'JAVA')
  event('DOCUMENT_RESET', 0, 'reset', sent.clientUpdateId, '33333333-3333-4333-8333-333333333333')
  vi.advanceTimersByTime(1000)
  expect(client.getSnapshot()).toMatchObject({ content: 'reset', pending: false })
  expect(client.getSnapshot().notice).toContain('reset')
  expect(transport.publish).toHaveBeenCalledTimes(2)
  client.stop()
})
it('rejects malformed server data and keeps oversized local content unsent', () => {
  event(); client.edit('😀'.repeat(16385), 'JAVA'); vi.advanceTimersByTime(1000)
  expect(transport.publish).toHaveBeenCalledTimes(1)
  expect(client.getSnapshot().notice).toContain('64 KiB')
  transport.receive!({ body: '{invalid' } as IMessage)
  expect(client.getSnapshot().connected).toBe(false)
})
it('does not replay uncertain writes after acknowledgement timeout', () => {
  event(); client.edit('uncertain', 'JAVA'); vi.advanceTimersByTime(300); vi.advanceTimersByTime(10000)
  expect(client.getSnapshot().notice).toContain('uncertain')
  expect(transport.publish).toHaveBeenCalledTimes(2)
})
it('reports dependency failures as uncertain instead of promising the write was rejected', () => {
  event(); client.edit('uncertain', 'JAVA'); vi.advanceTimersByTime(300)
  transport.receive!({ body: JSON.stringify({ type: 'ERROR', roomId: null, document: null,
    clientUpdateId: null, code: 'COLLABORATION_UNAVAILABLE' }) } as IMessage)
  expect(client.getSnapshot()).toMatchObject({ content: 'uncertain', pending: true, connected: false })
  expect(client.getSnapshot().notice).toContain('uncertain')
  vi.advanceTimersByTime(20000); expect(transport.publish).toHaveBeenCalledTimes(2)
})
it('handles an asynchronous snapshot send failure without an uncaught transport exception', () => {
  transport.publish.mockImplementationOnce(() => { throw new Error('Socket closed during subscribe') })
  expect(() => transport.onConnect!()).not.toThrow()
  expect(client.getSnapshot().connected).toBe(false)
  expect(client.getSnapshot().notice).toContain('unavailable')
  expect(transport.deactivate).toHaveBeenCalled()
})
