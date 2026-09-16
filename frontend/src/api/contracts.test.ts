import { expect, it, vi } from 'vitest'
import { me } from './auth'
import { createRoom, listRooms } from './rooms'
import { Session } from '../auth/session'

async function authenticatedSession() {
  const fetch = vi.fn()
    .mockResolvedValueOnce(Response.json({ accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 900,
      expiresAt: new Date(Date.now() + 900_000).toISOString() }))
    .mockResolvedValueOnce(Response.json({ id: 'user-1', email: 'user@example.test' }))
  vi.stubGlobal('fetch', fetch)
  const session = new Session()
  await session.login('user@example.test', 'test password long enough', new AbortController().signal)
  return { session, fetch }
}

it('rejects a malformed identity instead of establishing an unusable session', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({})))
  await expect(me('test-token', new AbortController().signal)).rejects.toThrow('unexpected response')
})
it('rejects malformed room lists before the UI accesses items', async () => {
  const { session, fetch } = await authenticatedSession()
  fetch.mockResolvedValueOnce(Response.json({ items: null, page: 0, size: 20, hasNext: false }))
  await expect(listRooms(session, 0, new AbortController().signal)).rejects.toThrow('unexpected response')
})
it('rejects incomplete creation responses without exposing a broken invitation', async () => {
  const { session, fetch } = await authenticatedSession()
  fetch.mockResolvedValueOnce(Response.json({ room: {}, invitationToken: null }, { status: 201 }))
  await expect(createRoom(session, 'Room', 'JAVA', new AbortController().signal)).rejects.toThrow('unexpected response')
  expect(fetch).toHaveBeenCalledTimes(3)
})
