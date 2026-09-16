import { randomBytes } from 'node:crypto'
import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'

async function registerAndLogin(page: Page) {
  const email = `browser-${randomBytes(8).toString('hex')}@example.test`
  const password = randomBytes(20).toString('hex')
  await page.goto('/register')
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('Account created. Log in to continue.')
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Your workspace' })).toBeVisible()
  return { email, password }
}

test('real browser registration, creation, invitation admission, navigation and memory-only sessions', async ({ page, browser }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.clock.install()
  const owner = await registerAndLogin(page)
  await page.getByLabel('Room name', { exact: true }).fill('Browser acceptance room')
  await page.getByLabel('Default language').selectOption('PYTHON')
  await page.getByRole('button', { name: 'Create room', exact: true }).click()
  const id = await page.getByLabel('Room ID', { exact: true }).inputValue()
  const invitation = await page.getByLabel('Invitation token', { exact: true }).inputValue()
  expect(invitation).toMatch(/^[A-Za-z0-9_-]{43}$/)
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByRole('button', { name: 'Copy invitation' }).click()
  await expect(page.getByRole('status')).toHaveText('Invitation copied.')
  // Compare as a boolean so failure reports cannot print the invitation.
  expect(await page.evaluate(async ({ id, invitation }) =>
    (await navigator.clipboard.readText()).replace(/\r\n/g, '\n') === `Room ID: ${id}\nInvitation token: ${invitation}`, { id, invitation })).toBe(true)
  await page.getByRole('link', { name: 'Open room', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Browser acceptance room' })).toBeVisible()
  await expect(page.getByText('Python', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length, document.cookie])).toEqual([0, 0, ''])
  expect(page.url().includes(invitation)).toBe(false)

  const guestContext = await browser.newContext({ baseURL: 'http://127.0.0.1:15173' })
  try {
    const guest = await guestContext.newPage()
    const account = await registerAndLogin(guest)
    // Full navigation intentionally loses the in-memory login; /login preserves only the room path.
    await guest.goto(`/rooms/${id}`)
    await expect(guest.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
    await guest.getByLabel('Email', { exact: true }).fill(account.email)
    await guest.getByLabel('Password', { exact: true }).fill(account.password)
    await guest.getByRole('button', { name: 'Log in', exact: true }).click()
    await expect(guest.getByRole('alert')).toContainText('Room is unavailable')
    await guest.getByRole('link', { name: 'Back to dashboard' }).click()
    await guest.getByLabel('Room ID to join').fill(id)
    await guest.getByLabel('Invitation token to join').fill('x'.repeat(43))
    await guest.getByRole('button', { name: 'Join room', exact: true }).click()
    await expect(guest.getByRole('alert')).toContainText('Room is unavailable')
    await guest.getByLabel('Invitation token to join').fill(invitation)
    await guest.getByRole('button', { name: 'Join room', exact: true }).click()
    await expect(guest.getByRole('heading', { name: 'Browser acceptance room' })).toBeVisible()
    await guest.getByRole('link', { name: 'Back to dashboard' }).click()
    await expect(guest.getByRole('link', { name: 'Browser acceptance room' })).toBeVisible()
    await guest.getByRole('button', { name: 'Log out' }).click()
    await expect(guest.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
    await expect(guest.getByText('Browser acceptance room')).toHaveCount(0)
  } finally { await guestContext.close() }

  await page.reload()
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
  await page.getByLabel('Email', { exact: true }).fill(owner.email)
  await page.getByLabel('Password', { exact: true }).fill(owner.password)
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Browser acceptance room' })).toBeVisible()
  await page.clock.fastForward(901_000)
  await expect(page.getByText('Your session expired. Please log in again.')).toBeVisible()
  expect(errors).toEqual([])
})

test('browser handles API loss and authenticated 401 without automatic writes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await registerAndLogin(page)
  await expect(page.getByText('No rooms on this page. Create one or join with an invitation.')).toBeVisible()
  let attempts = 0
  await page.route('**/api/rooms', async route => { attempts++; await route.abort('failed') })
  await page.getByLabel('Room name', { exact: true }).fill('Uncertain room')
  await page.getByRole('button', { name: 'Create room', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Cannot reach the server')
  await expect(page.getByRole('status')).toContainText('Retrying may create another room')
  expect(attempts).toBe(1)
  await page.route('**/api/rooms?*', route => route.fulfill({ status: 401, contentType: 'application/json',
    body: JSON.stringify({ message: 'Authentication required' }), headers: { 'Access-Control-Allow-Origin': 'http://127.0.0.1:15173' } }))
  await page.getByRole('button', { name: 'Refresh rooms' }).click()
  await expect(page.getByText('Your session expired. Please log in again.')).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('maximum-length room names remain readable on mobile', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await registerAndLogin(page)
  const name = 'R'.repeat(120)
  await page.getByLabel('Room name', { exact: true }).fill(name)
  await page.getByRole('button', { name: 'Create room', exact: true }).click()
  await page.getByRole('link', { name: 'Open room', exact: true }).click()
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('link', { name: 'Back to dashboard' }).click()
  await expect(page.getByRole('link', { name, exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})
