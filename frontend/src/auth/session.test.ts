import { beforeEach, describe, expect, it, vi } from 'vitest'
import { Session } from './session'

const user = { id: 'user-1', email: 'user@example.com' }
const response = () => ({ accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 900,
  expiresAt: new Date(Date.now() + 900_000).toISOString() })
let fetch: ReturnType<typeof vi.fn>
beforeEach(() => { fetch = vi.fn(); vi.stubGlobal('fetch', fetch) })
async function authenticate(session: Session, id = user.id) {
  fetch.mockResolvedValueOnce(Response.json(response())).mockResolvedValueOnce(Response.json({ ...user, id }))
  await session.login(user.email, 'test password long enough', new AbortController().signal)
}
describe('session ownership', () => {
  it('confirms the user via /me and exposes no token in the UI snapshot', async () => {
    const session = new Session()
    await authenticate(session)
    expect(session.getSnapshot()).toEqual({ user, expired: false })
    expect(fetch.mock.calls[1][0]).toContain('/auth/me')
  })
  it('rejects expired login responses', async () => {
    fetch.mockResolvedValueOnce(Response.json({ ...response(), expiresAt: new Date(0).toISOString() }))
    const session = new Session()
    await expect(session.login('a', 'b', new AbortController().signal)).rejects.toThrow('invalid login response')
    expect(session.getSnapshot().user).toBeNull()
  })
  it('expires before sending a request after a suspended tab resumes', async () => {
    vi.useFakeTimers()
    const session = new Session()
    await authenticate(session)
    vi.setSystemTime(Date.now() + 901_000)
    await expect(session.request('/rooms')).rejects.toMatchObject({ status: 401 })
    expect(fetch).toHaveBeenCalledTimes(2)
    expect(session.getSnapshot()).toEqual({ user: null, expired: true })
  })
  it('clears the session on an authenticated 401', async () => {
    const session = new Session()
    await authenticate(session)
    fetch.mockResolvedValueOnce(Response.json({ message: 'Unauthorized' }, { status: 401 }))
    await expect(session.request('/rooms')).rejects.toMatchObject({ status: 401 })
    expect(session.getSnapshot().expired).toBe(true)
  })
  it('keeps the session on dependency failure', async () => {
    const session = new Session()
    await authenticate(session)
    fetch.mockResolvedValueOnce(Response.json({ message: 'Unavailable' }, { status: 503 }))
    await expect(session.request('/rooms')).rejects.toMatchObject({ status: 503 })
    expect(session.getSnapshot().user).toEqual(user)
  })
  it.each([200, 401])('discards a stale %s response without changing a newer login', async status => {
    const session = new Session()
    await authenticate(session)
    let resolve!: (response: Response) => void
    fetch.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
    const pending = session.request('/rooms').catch(error => error)
    session.logout()
    await authenticate(session, 'user-2')
    resolve(Response.json({ old: true }, { status }))
    expect(await pending).toMatchObject({ name: 'AbortError' })
    expect(session.getSnapshot().user?.id).toBe('user-2')
  })
  it('cannot resurrect a session when logout happens during login', async () => {
    const session = new Session()
    fetch.mockResolvedValueOnce(Response.json(response()))
    let resolve!: (response: Response) => void
    fetch.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
    const pending = session.login('a', 'b', new AbortController().signal).catch(error => error)
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
    session.logout()
    resolve(Response.json(user))
    expect(await pending).toMatchObject({ name: 'AbortError' })
    expect(session.getSnapshot().user).toBeNull()
  })
})
