import { randomBytes } from 'node:crypto'
import { test, expect } from '@playwright/test'
import type { Page, WebSocketRoute } from '@playwright/test'

async function login(page: Page) {
  const email = `execution-${randomBytes(8).toString('hex')}@example.test`, password = randomBytes(20).toString('hex')
  await page.goto('/register'); await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('status')).toHaveText('Account created. Log in to continue.')
  await page.getByLabel('Email', { exact: true }).fill(email); await page.getByLabel('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Log in', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Your workspace' })).toBeVisible()
}
async function createRoom(page: Page) {
  await page.getByLabel('Room name', { exact: true }).fill('Execution acceptance')
  await page.getByRole('button', { name: 'Create room', exact: true }).click()
  const room = await page.getByLabel('Room ID', { exact: true }).inputValue()
  const invitation = await page.getByLabel('Invitation token', { exact: true }).inputValue()
  await page.getByRole('link', { name: 'Open room', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Run', exact: true })).toBeEnabled()
  return { room, invitation }
}
async function run(page: Page, language: string, source: string) {
  await page.getByLabel('Editor language').selectOption(language)
  await page.getByRole('textbox', { name: 'Source code', exact: true }).focus()
  await page.keyboard.press('Control+a'); await page.keyboard.insertText(source)
  await page.getByRole('button', { name: 'Run', exact: true }).click()
}
const status = (page: Page) => page.getByRole('status', { name: 'Execution status' })

test('two authorized browsers receive real Java execution progress and output automatically', async ({ page, browser }) => {
  test.setTimeout(90000)
  await login(page); const { room, invitation } = await createRoom(page)
  const context = await browser.newContext({ baseURL: 'http://127.0.0.1:15173' }); const guest = await context.newPage()
  try {
    await login(guest)
    await guest.getByLabel('Room ID to join', { exact: true }).fill(room)
    await guest.getByLabel('Invitation token to join', { exact: true }).fill(invitation)
    await guest.getByRole('button', { name: 'Join room', exact: true }).click()
    await expect(guest.getByRole('button', { name: 'Run', exact: true })).toBeEnabled()
    await run(page, 'JAVA', 'public class Main { public static void main(String[] a) throws Exception { Thread.sleep(1500); System.out.println("shared Java result"); } }')
    await expect(status(page)).toHaveText('RUNNING')
    for (const peer of [page, guest]) {
      await expect(status(peer)).toHaveText('SUCCEEDED', { timeout: 20000 })
      await expect(peer.getByLabel('Standard output', { exact: true })).toHaveText('shared Java result\n')
    }
  } finally { await context.close() }
})

test('real Python success, runtime failure, Java compilation failure and timeout reach the UI', async ({ page }) => {
  test.setTimeout(90000)
  await login(page); await createRoom(page)
  await run(page, 'PYTHON', 'import time\ntime.sleep(1)\nprint("python result")')
  await expect(status(page)).toHaveText('SUCCEEDED', { timeout: 20000 })
  await expect(page.getByLabel('Standard output', { exact: true })).toHaveText('python result\n')
  await run(page, 'PYTHON', 'raise ValueError("expected runtime failure")')
  await expect(status(page)).toHaveText('FAILED', { timeout: 20000 })
  await expect(page.getByLabel('Standard error', { exact: true })).toContainText('expected runtime failure')
  await run(page, 'JAVA', 'public class Main { invalid code }')
  await expect(page.getByRole('region', { name: 'Output', exact: true })).toContainText('COMPILATION_ERROR', { timeout: 20000 })
  await run(page, 'PYTHON', 'while True: pass')
  await expect(status(page)).toHaveText('TIMED_OUT', { timeout: 20000 })
})

test('missed notifications recover through Refresh Status and reconnect without another POST', async ({ page }) => {
  test.setTimeout(90000)
  let connection: WebSocketRoute | undefined, terminalDropped = 0, posts = 0
  await page.routeWebSocket('ws://127.0.0.1:18080/ws', socket => {
    connection = socket
    const server = socket.connectToServer()
    server.onMessage(message => {
      if (message.toString().includes('/executions')) {
        if (message.toString().includes('"status":"SUCCEEDED"')) terminalDropped++
        return
      }
      socket.send(message)
    })
  })
  page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/executions')) posts++ })
  await login(page); await createRoom(page)
  await run(page, 'PYTHON', 'import time\ntime.sleep(2)\nprint("rest recovery")')
  await expect(status(page)).toHaveText('RUNNING')
  await expect.poll(() => terminalDropped, { timeout: 20000 }).toBeGreaterThan(0)
  await page.getByRole('button', { name: 'Refresh Status', exact: true }).click()
  await expect(status(page)).toHaveText('SUCCEEDED')
  await expect(page.getByLabel('Standard output', { exact: true })).toHaveText('rest recovery\n')
  const previousTerminalCount = terminalDropped
  await run(page, 'PYTHON', 'import time\ntime.sleep(2)\nprint("reconnect recovery")')
  await expect(status(page)).toHaveText('RUNNING')
  await expect.poll(() => terminalDropped, { timeout: 20000 }).toBeGreaterThan(previousTerminalCount)
  // This second result is still stale locally: reconnect itself must fetch it.
  await expect(status(page)).toHaveText('RUNNING')
  await connection!.close({ code: 1011, reason: 'Test reconnect' })
  await expect(page.getByRole('button', { name: 'Reconnect', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Reconnect', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Run', exact: true })).toBeEnabled()
  await expect(status(page)).toHaveText('SUCCEEDED')
  await expect(page.getByLabel('Standard output', { exact: true })).toHaveText('reconnect recovery\n')
  expect(posts).toBe(2) // Exactly the two deliberate runs, no recovery resubmission.
})
