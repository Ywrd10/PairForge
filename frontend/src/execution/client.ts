import type { Session } from '../auth/session'
import { ApiError } from '../api/client'
import type { Language } from '../api/rooms'
import * as api from '../api/executions'

interface State {
  items: api.Summary[]; selected: string | null; detail: api.Detail | null
  submitting: boolean; refreshing: boolean; notice: string; refreshError: string; delayed: boolean
}
export class ExecutionClient {
  private state: State = { items: [], selected: null, detail: null, submitting: false, refreshing: false, notice: '', refreshError: '', delayed: false }
  private listeners = new Set<() => void>()
  private controller = new AbortController()
  private active = false
  private generation = 0
  private selection = 0
  private queued = false
  private flight = false
  private timer: ReturnType<typeof setTimeout> | undefined
  private delay: ReturnType<typeof setTimeout> | undefined
  private revisions = new Map<string, number>()
  constructor(private session: Session, private room: string) {}
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener) } }
  private emit(next: Partial<State>) { this.state = { ...this.state, ...next }; this.listeners.forEach(listener => listener()) }
  start() {
    this.active = true; this.controller = new AbortController(); this.generation++; this.flight = false; this.queued = false
    this.emit({ submitting: false, refreshing: false, delayed: false })
  }
  stop() {
    this.active = false; this.generation++; this.controller.abort(); clearTimeout(this.timer); clearTimeout(this.delay)
    this.timer = undefined; this.delay = undefined
  }
  event = (event: api.ExecutionEvent) => {
    if (!this.active || event.roomId !== this.room || (this.revisions.get(event.executionId) ?? -1) >= event.stateRevision) return
    this.revisions.set(event.executionId, event.stateRevision)
    if (this.revisions.size > 128) this.revisions.delete(this.revisions.keys().next().value!)
    this.schedule()
  }
  private schedule() { if (!this.timer) this.timer = setTimeout(() => { this.timer = undefined; void this.refresh() }, 100) }
  select = (id: string) => {
    if (!this.active || !api.uuid(id)) return
    this.selection++; this.emit({ selected: id, detail: null, delayed: false }); clearTimeout(this.delay); this.delay = undefined; void this.refresh()
  }
  refresh = async () => {
    if (!this.active) return
    if (this.flight) { this.queued = true; return }
    this.flight = true; const generation = this.generation, selection = this.selection
    const signal = this.controller.signal
    this.emit({ refreshing: true })
    try {
      const page = await api.history(this.session, this.room, signal)
      if (!this.active || generation !== this.generation) return
      const previous = new Map(this.state.items.map(item => [item.id, item]))
      const items = page.items.map(item => {
        const old = previous.get(item.id)
        return old && (old.stateRevision > item.stateRevision || api.terminal(old.status)) ? old : item
      })
      this.emit({ items })
      if (!this.state.selected && items.length) this.emit({ selected: items[0].id })
      const id = this.state.selected
      if (id) {
        const next = await api.get(this.session, this.room, id, signal)
        if (!this.active || generation !== this.generation || selection !== this.selection || id !== this.state.selected) return
        const old = this.state.detail
        if (!old || next.stateRevision >= old.stateRevision && !api.terminal(old.status)) this.emit({ detail: next })
        const current = this.state.detail!
        if (api.terminal(current.status)) { clearTimeout(this.delay); this.delay = undefined; this.emit({ delayed: false }) }
        else if (!this.delay) this.delay = setTimeout(() => {
          this.emit({ delayed: true }); // One hint only; never converts unknown progress into failure or polls.
        }, 30000)
      }
      this.emit({ refreshError: '' })
    } catch (error) {
      if (this.active && generation === this.generation && !signal.aborted)
        this.emit({ refreshError: error instanceof ApiError && error.status === 404
          ? 'Execution is unavailable. Check recent executions before submitting again.'
          : 'Status could not be refreshed. Use Refresh Status after recovery.' })
    } finally {
      if (this.active && generation === this.generation) {
        this.flight = false; this.emit({ refreshing: false })
        if (this.queued) { this.queued = false; this.schedule() }
      }
    }
  }
  run = async (source: string, language: Language) => {
    if (!this.active || this.state.submitting) return
    if (!api.validSource(source)) { this.emit({ notice: 'Source must be valid text without NUL and at most 64 KiB UTF-8.' }); return }
    const generation = this.generation, selection = this.selection
    this.emit({ submitting: true, notice: '' })
    try {
      const receipt = await api.submit(this.session, this.room, source, language, this.controller.signal)
      if (this.active && generation === this.generation && selection === this.selection) this.select(receipt.executionId)
    } catch (error) {
      if (!this.active || generation !== this.generation) return
      const known = error instanceof ApiError ? error.executionId : undefined
      if (known && selection === this.selection) this.select(known)
      this.emit({ notice: error instanceof ApiError && error.executionStatus === 'FAILED' && error.outcomeUnknown === false
        ? `Submission failed: ${error.failureReason ?? error.message}. Inspect the selected execution before running again.`
        : error instanceof ApiError && error.status >= 400 && error.status < 500
          ? error.message + (error.retryAfter ? ` Retry after ${error.retryAfter} seconds.` : '')
          : 'Submission outcome may be uncertain. Check the selected execution and recent executions before running again. No automatic resubmission was made.' })
    } finally {
      if (this.active && generation === this.generation) { this.emit({ submitting: false }); void this.refresh() }
    }
  }
}
