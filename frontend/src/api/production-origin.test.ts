import { afterEach, expect, it, vi } from 'vitest'

afterEach(() => { vi.unstubAllEnvs(); vi.resetModules() })

it('sends production registration and WebSockets to the page origin without a build-time override', async () => {
  vi.stubEnv('PROD', true)
  vi.stubEnv('VITE_API_BASE_URL', '')
  vi.stubGlobal('location', new URL('https://demo.example.com/register'))
  const fetch = vi.fn().mockResolvedValue(Response.json({}))
  vi.stubGlobal('fetch', fetch)
  const client = await import('./client')
  await client.request('/auth/register', { method: 'POST', body: {} })
  expect(fetch).toHaveBeenCalledWith('https://demo.example.com/api/auth/register', expect.anything())
  expect(client.collaborationUrl()).toBe('wss://demo.example.com/ws')
})

it('retains an explicit API origin for local production-build browser fixtures', async () => {
  vi.stubEnv('PROD', true)
  vi.stubEnv('VITE_API_BASE_URL', 'http://127.0.0.1:18080/')
  const client = await import('./client')
  expect(client.collaborationUrl()).toBe('ws://127.0.0.1:18080/ws')
})
