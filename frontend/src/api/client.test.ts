import { describe, expect, it, vi } from 'vitest'
import { ApiError, request } from './client'

describe('API boundary', () => {
  it('preserves a validated execution ID and dispatch uncertainty without trusting malformed IDs', async () => {
    const id='11111111-1111-4111-8111-111111111111'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ message: 'Unknown outcome', executionId: id, outcomeUnknown: true }, { status: 503 })))
    await expect(request('/rooms/test/executions')).rejects.toMatchObject({ executionId: id, outcomeUnknown: true, status: 503 })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ executionId: '../outside', outcomeUnknown: 'yes' }, { status: 503 })))
    await expect(request('/rooms/test/executions')).rejects.toMatchObject({ executionId: undefined, outcomeUnknown: undefined })
  })
  it('sends JSON and bearer credentials only to the configured API without cookies or redirects', async () => {
    const fetch = vi.fn().mockResolvedValue(Response.json({ id: 'room' }))
    vi.stubGlobal('fetch', fetch)
    await request('/rooms', { method: 'POST', token: 'test-token', body: { name: 'room' } })
    expect(fetch).toHaveBeenCalledExactlyOnceWith('http://127.0.0.1:8080/api/rooms', expect.objectContaining({
      method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error',
      headers: expect.objectContaining({ Authorization: 'Bearer test-token', 'Content-Type': 'application/json' }),
      body: '{"name":"room"}',
    }))
  })
  it('retains safe field errors, request ID and throttling information', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ message: 'Slow down', fieldErrors: { email: 'Invalid' } },
      { status: 429, headers: { 'Retry-After': '60', 'X-Request-ID': 'request-1' } })))
    await expect(request('/auth/login')).rejects.toMatchObject({ status: 429, message: 'Slow down',
      fieldErrors: { email: 'Invalid' }, retryAfter: '60', requestId: 'request-1' })
  })
  it('handles non-JSON errors without rendering raw server HTML', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>internal details</html>', { status: 503 })))
    await expect(request('/rooms')).rejects.toMatchObject({ status: 503, message: 'The request could not be completed.' })
  })
  it('does not retry an uncertain write', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('offline'))
    vi.stubGlobal('fetch', fetch)
    await expect(request('/rooms', { method: 'POST', body: {} })).rejects.toBeInstanceOf(ApiError)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('rejects malformed success JSON', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('bad', { status: 201 })))
    await expect(request('/rooms')).rejects.toMatchObject({ status: 201, message: 'The server returned an unreadable response.' })
  })
  it('distinguishes caller cancellation from network failure', async () => {
    const controller = new AbortController()
    controller.abort()
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(controller.signal.reason))
    await expect(request('/rooms', { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' })
  })
  it('bounds stalled requests with a timeout', async () => {
    const controller = new AbortController()
    vi.spyOn(AbortSignal, 'timeout').mockReturnValue(controller.signal)
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise((_, reject) => {
      controller.signal.addEventListener('abort', () => reject(controller.signal.reason))
    })))
    const result = request('/rooms')
    controller.abort()
    await expect(result).rejects.toMatchObject({ message: 'The request timed out. Its outcome may be uncertain.' })
  })
})
