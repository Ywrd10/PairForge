import { Client } from '@stomp/stompjs'
import type { IMessage } from '@stomp/stompjs'
import { collaborationUrl, isRecord } from '../api/client'
import type { Language } from '../api/rooms'
import type { Session } from '../auth/session'

interface Document { content: string; language: Language; version: number; generationId: string }
interface Event { type: string; roomId: string | null; document: Document | null; clientUpdateId: string | null; code: string | null }
export interface CollaborationState {
  content: string; language: Language; ready: boolean; connected: boolean; pending: boolean; notice: string
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const bytes = (value: string) => new TextEncoder().encode(value).length
const validText = (value: string) => !value.includes('\0') && bytes(value) <= 65536
  && new TextDecoder().decode(new TextEncoder().encode(value)) === value
function parse(body: string): Event {
  const value: unknown = JSON.parse(body)
  if (!isRecord(value) || !['SNAPSHOT', 'UPDATED', 'DOCUMENT_RESET', 'ERROR', 'REJECTED'].includes(String(value.type))
    || !(value.roomId === null || typeof value.roomId === 'string' && uuid.test(value.roomId))
    || !(value.clientUpdateId === null || typeof value.clientUpdateId === 'string' && uuid.test(value.clientUpdateId))
    || !(value.code === null || typeof value.code === 'string')) throw new Error('Invalid event')
  if (['SNAPSHOT', 'UPDATED', 'DOCUMENT_RESET'].includes(String(value.type))) {
    const doc = value.document
    if (!isRecord(doc) || typeof doc.content !== 'string' || bytes(doc.content) > 65536
      || !validText(doc.content)
      || !['JAVA', 'PYTHON'].includes(String(doc.language)) || typeof doc.version !== 'number'
      || !Number.isSafeInteger(doc.version) || doc.version < 0 || typeof doc.generationId !== 'string' || !uuid.test(doc.generationId)) {
      throw new Error('Invalid document')
    }
  } else if (value.document !== null) throw new Error('Invalid error')
  return value as unknown as Event
}

// One connection, one in-flight update, and one coalesced local draft. No offline replay.
export class CollaborationClient {
  private state: CollaborationState
  private readonly listeners = new Set<() => void>()
  private transport: Client | null = null
  private document: Document | null = null
  private readonly retired = new Set<string>()
  private timer: ReturnType<typeof setTimeout> | undefined
  private deadline: ReturnType<typeof setTimeout> | undefined
  private sequence = 0
  private flight: { id: string; content: string; language: Language } | null = null
  constructor(private session: Session, private room: string, content: string, language: Language) {
    this.state = { content, language, ready: false, connected: false, pending: false, notice: 'Connecting…' }
  }
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private emit(next: Partial<CollaborationState>) {
    this.state = { ...this.state, ...next }; this.listeners.forEach(listener => listener())
  }
  start() {
    const client = new Client({ brokerURL: collaborationUrl(), reconnectDelay: 0, connectionTimeout: 8000,
      heartbeatIncoming: 10000, heartbeatOutgoing: 10000, discardWebsocketOnCommFailure: true,
      debug: () => {}, // STOMP debug logs contain bearer credentials and source.
    })
    this.transport = client
    const active = () => this.transport === client
    client.beforeConnect = () => {
      if (!active()) return
      try { client.connectHeaders = this.session.collaborationHeaders() }
      catch { this.fail('Collaboration unavailable. Please log in again.') }
    }
    client.onConnect = () => {
      if (!active()) return
      client.connectHeaders = {}
      const receive = (message: IMessage) => {
        if (!active()) return
        try { this.receive(parse(message.body)) } catch { this.fail('Invalid collaboration response. Local edits are not synchronized.') }
      }
      try {
        client.subscribe('/user/queue/collaboration', receive)
        client.subscribe(`/topic/rooms/${this.room}/document`, receive)
        client.publish({ destination: `/app/rooms/${this.room}/snapshot`, body: '{}', headers: { 'content-type': 'application/json' } })
      } catch { this.fail('Collaboration unavailable. Reopen the room to load server state.') }
    }
    client.onStompError = () => { if (active()) this.fail('Collaboration request rejected. Local edits are not synchronized.') }
    client.onWebSocketClose = () => { if (active()) this.fail('Disconnected. Local edits are not synchronized. Reopen the room to load server state.') }
    client.onWebSocketError = () => { if (active()) this.fail('Collaboration unavailable. Local edits are not synchronized.') }
    this.deadline = setTimeout(() => this.fail('Collaboration timed out. Local edits are not synchronized.'), 10000)
    try { client.activate() } catch { this.fail('Collaboration unavailable. Please log in again.') }
  }
  private receive(event: Event) {
    if (event.roomId !== null && event.roomId !== this.room) throw new Error('Wrong room')
    if (event.type === 'ERROR' || event.type === 'REJECTED') {
      this.fail(event.code === 'COLLABORATION_UNAVAILABLE'
        ? 'Collaboration unavailable. Server outcome is uncertain; reopen the room to load state.'
        : 'Collaboration update was not accepted. Local edits are not synchronized. Reopen the room to load server state.')
      return
    }
    const doc = event.document!
    if (this.retired.has(doc.generationId)) return
    const reset = event.type === 'DOCUMENT_RESET' || event.code === 'DOCUMENT_RESET'
    const acknowledgement = event.clientUpdateId === this.flight?.id
    const dirtyAfterSend = acknowledgement && this.state.pending && this.flight !== null
      && (this.state.content !== this.flight.content || this.state.language !== this.flight.language)
    if (acknowledgement) { this.flight = null; clearTimeout(this.deadline) }
    let changed = false
    if (!this.document || doc.generationId !== this.document.generationId) {
      if (this.document) {
        if (event.type !== 'DOCUMENT_RESET') return
        this.retired.add(this.document.generationId)
        this.flight = null; clearTimeout(this.deadline); clearTimeout(this.timer)
      }
      this.document = doc; changed = true
    } else if (doc.version > this.document.version) { this.document = doc; changed = true }
    if (event.type === 'SNAPSHOT') {
      clearTimeout(this.deadline)
      this.emit({ ready: true, connected: true })
    }
    if (changed || event.type === 'SNAPSHOT') {
      const overwrite = this.state.pending && !acknowledgement
      if (!dirtyAfterSend || reset) {
        clearTimeout(this.timer)
        this.emit({ content: this.document.content, language: this.document.language, pending: false,
          notice: reset ? 'Connected — document reset: a new Redis document was initialized.'
            : overwrite ? 'Another accepted update replaced your pending edits.' : 'Connected — synchronized in Redis.' })
      }
    }
    if (this.state.ready && this.state.connected && dirtyAfterSend && !reset) {
      this.emit({ pending: true, notice: 'Unsynchronized local edits…' }); this.schedule()
    }
  }
  edit(content: string, language: Language) {
    this.emit({ content, language, pending: true, notice: 'Unsynchronized local edits…' })
    if (!validText(content)) {
      clearTimeout(this.timer)
      this.emit({ notice: 'Document must be valid text without NUL and at most 64 KiB UTF-8. Edits are not synchronized.' }); return
    }
    if (!this.state.connected) { this.emit({ notice: 'Disconnected. Local edits are not synchronized.' }); return }
    this.schedule()
  }
  private schedule() { clearTimeout(this.timer); this.timer = setTimeout(() => this.send(), 300) }
  private send() {
    if (!this.document || !this.transport?.connected || this.flight || !this.state.pending || !validText(this.state.content)) return
    const id = crypto.randomUUID()
    this.flight = { id, content: this.state.content, language: this.state.language }
    this.deadline = setTimeout(() => this.fail('Update acknowledgement timed out. Server outcome is uncertain; reopen the room to load state.'), 10000)
    try {
      this.transport.publish({ destination: `/app/rooms/${this.room}/update`, headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ generationId: this.document.generationId, sequence: ++this.sequence, clientUpdateId: id,
          content: this.state.content, language: this.state.language }) })
    } catch { this.fail('Update could not be sent. Local edits are not synchronized.') }
  }
  private fail(notice: string) { this.stop(); this.emit({ connected: false, notice }) }
  stop() {
    clearTimeout(this.timer); clearTimeout(this.deadline)
    const client = this.transport; this.transport = null; this.flight = null
    if (client) { client.connectHeaders = {}; void client.deactivate({ force: true }) }
  }
}
