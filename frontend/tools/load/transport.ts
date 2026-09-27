import { Client } from '@stomp/stompjs'
import type { IStompSocket } from '@stomp/stompjs'
import WebSocket from 'ws'
import { until } from './core.ts'

export const base = 'http://127.0.0.1:18080'
export const origin = 'http://127.0.0.1:15173'
export interface Document { content: string; language: string; generationId: string; version: number }
export interface DocumentEvent { type: string; roomId: string; clientUpdateId: string | null; document: Document | null }
export interface ExecutionEvent { roomId: string; executionId: string; status: string; stateRevision: number }

export async function request(path: string, token: string | undefined, body: unknown, signal: AbortSignal, deadlineMs: number) {
  return fetch(`${base}/api${path}`, { method: body === undefined ? 'GET' : 'POST',
    headers: { Origin: origin, 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.any([signal, AbortSignal.timeout(deadlineMs)]) })
}
export async function json<T>(path: string, token: string | undefined, body: unknown, signal: AbortSignal, deadlineMs: number): Promise<T> {
  const response = await request(path, token, body, signal, deadlineMs)
  if (!response.ok) throw new Error(`Load HTTP request failed (${response.status})`)
  return await response.json() as T
}

export class Peer {
  readonly room: string
  private deadlineMs: number
  private client: Client
  private socket?: WebSocket
  private closing = false
  private sequence = 0
  private error?: Error
  private synchronized = false
  document?: Document
  disconnects = 0
  errors = 0
  onDocument: (event: DocumentEvent) => void = () => {}
  onExecution: (event: ExecutionEvent) => void = () => {}
  constructor(room: string, token: string, deadlineMs: number) {
    this.room = room; this.deadlineMs = deadlineMs
    this.client = new Client({
      webSocketFactory: () => {
        this.socket = new WebSocket(`${base.replace('http:', 'ws:')}/ws`, ['v12.stomp'], { origin, maxPayload: 400000 })
        return this.socket as unknown as IStompSocket
      }, connectHeaders: { Authorization: `Bearer ${token}` }, reconnectDelay: 0,
      connectionTimeout: deadlineMs, heartbeatIncoming: 10000, heartbeatOutgoing: 10000,
      debug: () => {},
    })
    const fail = () => { if (!this.closing) { this.errors++; this.error = new Error('Load WebSocket protocol/transport error') } }
    this.client.onStompError = fail
    this.client.onWebSocketError = fail
    this.client.onWebSocketClose = () => { if (!this.closing) { this.disconnects++; fail() } }
    this.client.onConnect = () => {
      try {
        this.client.connectHeaders = {}
        const receive = (body: string) => {
          try {
            const event = JSON.parse(body) as DocumentEvent
            if (event.roomId !== room || !event.document || !['SNAPSHOT', 'UPDATED', 'DOCUMENT_RESET'].includes(event.type)) throw new Error('Rejected document')
            if (this.document && event.document.generationId === this.document.generationId && event.document.version < this.document.version) return
            this.document = event.document
            if (event.type === 'SNAPSHOT') this.synchronized = true
            this.onDocument(event)
          } catch { fail() }
        }
        this.client.subscribe('/user/queue/collaboration', event => receive(event.body))
        this.client.subscribe(`/topic/rooms/${room}/document`, event => receive(event.body))
        this.client.subscribe(`/topic/rooms/${room}/executions`, event => {
          try {
            const value = JSON.parse(event.body) as ExecutionEvent
            if (value.roomId !== room || !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT'].includes(value.status)) throw new Error('Invalid execution event')
            this.onExecution(value)
          } catch { fail() }
        })
        this.client.publish({ destination: `/app/rooms/${room}/snapshot`, body: '{}', headers: { 'content-type': 'application/json' } })
      } catch { fail() }
    }
  }
  assertHealthy() { if (this.error) throw this.error }
  async open(signal: AbortSignal) {
    this.client.activate()
    await until(() => { this.assertHealthy(); return this.synchronized }, this.deadlineMs, signal)
  }
  update(id: string, content: string) {
    this.assertHealthy()
    if (!this.document || !this.synchronized) throw new Error('Peer is not synchronized')
    this.client.publish({ destination: `/app/rooms/${this.room}/update`, headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ generationId: this.document.generationId, sequence: ++this.sequence, clientUpdateId: id, content, language: 'PYTHON' }) })
  }
  async close() {
    this.closing = true
    this.client.connectHeaders = {}
    // terminate also bounds cleanup if the remote peer never replies to CLOSE.
    this.socket?.terminate()
    await this.client.deactivate({ force: true })
  }
}
