import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import App from './App'
import { AuthProvider } from './auth/AuthProvider'

const room = { id: '11111111-1111-1111-1111-111111111111', ownerId: 'user-1', name: 'Practice room',
  language: 'JAVA', createdAt: '2026-09-16T12:00:00Z', updatedAt: '2026-09-16T12:00:00Z' }
let fetch: ReturnType<typeof vi.fn>
beforeEach(() => {
  fetch = vi.fn(async (url: string) => {
    if (url.endsWith('/auth/login')) return Response.json({ accessToken: 'test-token', tokenType: 'Bearer',
      expiresAt: new Date(Date.now() + 900_000).toISOString(), expiresIn: 900 })
    if (url.endsWith('/auth/me')) return Response.json({ id: 'user-1', email: 'user@example.com' })
    if (url.includes('/rooms?')) return Response.json({ items: [], page: 0, size: 20, hasNext: false })
    if (url.endsWith(`/rooms/${room.id}`)) return Response.json(room)
    throw new Error('Unexpected test request')
  })
  vi.stubGlobal('fetch', fetch)
})
function open(path = '/login') {
  return render(<MemoryRouter initialEntries={[path]}><AuthProvider><App /></AuthProvider></MemoryRouter>)
}
async function login() {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email'), 'user@example.com')
  await user.type(screen.getByLabelText('Password'), 'test password long enough')
  await user.click(screen.getByRole('button', { name: 'Log in' }))
}

it('guards a direct room URL and returns to it after login', async () => {
  open(`/rooms/${room.id}`)
  expect(screen.getByRole('heading', { name: 'Welcome back' })).toBeTruthy()
  await login()
  expect(await screen.findByRole('heading', { name: room.name })).toBeTruthy()
})
it('validates registration password bytes without trimming it', async () => {
  open('/register')
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.com' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: '😀'.repeat(19) } })
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', expect.stringContaining('72 UTF-8 bytes'))
  expect(fetch).not.toHaveBeenCalled()
})
it('registers then requires a separate login and clears the password', async () => {
  open('/register')
  fetch.mockResolvedValueOnce(Response.json({ id: 'new', email: 'user@example.com' }, { status: 201 }))
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.com' } })
  const password = '  untrimmed password  '
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }))
  expect(await screen.findByRole('status')).toHaveProperty('textContent', 'Account created. Log in to continue.')
  expect(screen.getByLabelText('Password')).toHaveProperty('value', '')
  expect(JSON.parse(fetch.mock.calls[0][1].body).password).toBe(password)
  expect(fetch).toHaveBeenCalledTimes(1)
})
it('shows field errors and Retry-After without logging the user in', async () => {
  open()
  fetch.mockResolvedValueOnce(Response.json({ message: 'Too many attempts', fieldErrors: { email: 'Try later' } },
    { status: 429, headers: { 'Retry-After': '60' } }))
  await login()
  const alert = await screen.findByRole('alert')
  expect(alert.textContent).toContain('Try again after 60 seconds')
  expect(alert.textContent).toContain('email: Try later')
  expect(screen.queryByRole('button', { name: 'Log out' })).toBeNull()
})
it('prevents duplicate creation and displays the one-time invitation', async () => {
  open()
  await login()
  await screen.findByText('No rooms on this page. Create one or join with an invitation.')
  let resolve!: (value: Response) => void
  fetch.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
  fireEvent.change(screen.getByLabelText('Room name'), { target: { value: room.name } })
  const button = screen.getByRole('button', { name: 'Create room' })
  fireEvent.click(button)
  fireEvent.click(button)
  expect(fetch.mock.calls.filter(call => call[1]?.method === 'POST' && call[0].endsWith('/rooms'))).toHaveLength(1)
  await act(async () => resolve(Response.json({ room, invitationToken: 'a'.repeat(43) }, { status: 201 })))
  expect(await screen.findByLabelText('Invitation token')).toHaveProperty('value', 'a'.repeat(43))
  vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValueOnce(new Error('Clipboard unavailable'))
  fireEvent.click(screen.getByRole('button', { name: 'Copy invitation' }))
  expect(await screen.findByRole('status')).toHaveProperty('textContent', 'Copy was unavailable. Select and copy the fields above.')
  await userEvent.click(screen.getByRole('link', { name: 'Open room' }))
  await screen.findByRole('heading', { name: room.name })
  expect(screen.queryByLabelText('Invitation token')).toBeNull()
})
it('warns about an uncertain create without retrying automatically', async () => {
  open()
  await login()
  await screen.findByText('No rooms on this page. Create one or join with an invitation.')
  fetch.mockRejectedValueOnce(new TypeError('Network disconnected'))
  fireEvent.change(screen.getByLabelText('Room name'), { target: { value: room.name } })
  fireEvent.click(screen.getByRole('button', { name: 'Create room' }))
  expect(await screen.findByRole('status')).toHaveProperty('textContent', expect.stringContaining('Retrying may create another room'))
  expect(fetch.mock.calls.filter(call => call[0].endsWith('/rooms'))).toHaveLength(1)
})
it('does not claim an uncertain write when room-name validation rejects the form locally', async () => {
  open()
  await login()
  await screen.findByText('No rooms on this page. Create one or join with an invitation.')
  fireEvent.change(screen.getByLabelText('Room name'), { target: { value: '   ' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create room' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Enter a room name.')
  expect(screen.queryByText(/The room may have been created/)).toBeNull()
  expect(fetch.mock.calls.filter(call => call[0].endsWith('/rooms'))).toHaveLength(0)
})
it('paginates room lists and lets a failed read recover', async () => {
  open()
  await login()
  await screen.findByText('No rooms on this page. Create one or join with an invitation.')
  fetch.mockResolvedValueOnce(Response.json({ items: [room], page: 0, size: 20, hasNext: true }))
  fireEvent.click(screen.getByRole('button', { name: 'Refresh rooms' }))
  await screen.findByRole('link', { name: room.name })
  fetch.mockResolvedValueOnce(Response.json({ message: 'Service unavailable' }, { status: 503 }))
  fireEvent.click(screen.getByRole('button', { name: 'Next' }))
  await screen.findByRole('alert')
  expect(fetch.mock.lastCall?.[0]).toContain('page=1&size=20')
  fireEvent.click(screen.getByRole('button', { name: 'Refresh rooms' }))
  await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
})
it('clears protected content on logout and rejects a stale response', async () => {
  open()
  await login()
  await screen.findByText('No rooms on this page. Create one or join with an invitation.')
  let resolve!: (value: Response) => void
  fetch.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
  fireEvent.click(screen.getByRole('button', { name: 'Refresh rooms' }))
  fireEvent.click(screen.getByRole('button', { name: 'Log out' }))
  await act(async () => resolve(Response.json({ items: [room], page: 0, size: 20, hasNext: false })))
  expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeTruthy()
  expect(screen.queryByText(room.name)).toBeNull()
})
it('keeps the dashboard usable when a successful response has malformed room data', async () => {
  open()
  await login()
  await screen.findByText('No rooms on this page. Create one or join with an invitation.')
  fetch.mockResolvedValueOnce(Response.json({ items: null, page: 0, size: 20, hasNext: false }))
  fireEvent.click(screen.getByRole('button', { name: 'Refresh rooms' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', expect.stringContaining('unexpected response'))
  expect(screen.getByRole('heading', { name: 'Your workspace' })).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Refresh rooms' }))
  await screen.findByText('No rooms on this page. Create one or join with an invitation.')
  expect(screen.queryByRole('alert')).toBeNull()
})
it('enforces expiry when focus returns to a suspended tab', async () => {
  open()
  await login()
  await screen.findByRole('heading', { name: 'Your workspace' })
  vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 901_000)
  fireEvent.focus(window)
  expect(await screen.findByText('Your session expired. Please log in again.')).toBeTruthy()
})
