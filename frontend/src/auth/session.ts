import * as auth from '../api/auth'
import { ApiError, request } from '../api/client'
import type { RequestOptions } from '../api/client'

export interface SessionState { user: auth.User | null; expired: boolean }

// Session ownership guards both network responses and in-flight login attempts.
export class Session {
  private state: SessionState = { user: null, expired: false }
  private token = ''
  private expiresAt = 0
  private revision = 0
  private controller = new AbortController()
  private listeners = new Set<() => void>()
  getSnapshot = () => this.state
  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private emit() { this.listeners.forEach(listener => listener()) }
  logout = (expired = false) => {
    this.revision++
    this.controller.abort()
    this.controller = new AbortController()
    this.token = ''
    this.expiresAt = 0
    this.state = { user: null, expired }
    this.emit()
  }
  checkExpiry = () => {
    if (this.token && Date.now() >= this.expiresAt) this.logout(true)
  }
  millisecondsRemaining = () => Math.max(0, this.expiresAt - Date.now())

  async login(email: string, password: string, signal: AbortSignal) {
    this.logout()
    const revision = this.revision
    const combined = AbortSignal.any([signal, this.controller.signal])
    const result = await auth.login(email, password, combined)
    const expiry = Date.parse(result.expiresAt)
    if (!result.accessToken || result.tokenType !== 'Bearer' || !Number.isFinite(expiry) || expiry <= Date.now()) {
      throw new ApiError('The server returned an invalid login response.')
    }
    const user = await auth.me(result.accessToken, combined)
    combined.throwIfAborted()
    if (revision !== this.revision) throw new DOMException('Session changed', 'AbortError')
    if (expiry <= Date.now()) throw new ApiError('Your login expired. Please log in again.')
    this.token = result.accessToken
    this.expiresAt = expiry
    this.state = { user, expired: false }
    this.emit()
  }

  async request<T>(path: string, options: Omit<RequestOptions, 'token'> = {}): Promise<T> {
    this.checkExpiry()
    if (!this.token) throw new ApiError('Please log in again.', 401)
    const revision = this.revision
    const signal = options.signal
      ? AbortSignal.any([options.signal, this.controller.signal]) : this.controller.signal
    try {
      const result = await request<T>(path, { ...options, token: this.token, signal })
      this.checkExpiry()
      signal.throwIfAborted()
      if (revision !== this.revision) throw new DOMException('Session changed', 'AbortError')
      return result
    } catch (error) {
      if (error instanceof ApiError && error.status === 401 && revision === this.revision) this.logout(true)
      throw error
    }
  }
}
