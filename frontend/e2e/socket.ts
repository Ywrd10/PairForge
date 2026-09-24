import type { WebSocketRoute } from '@playwright/test'

// Playwright's route.close() closes only that side of the proxy. A simulated
// disconnect must also close the upstream connection rather than leak sessions.
export function connectSocket(browser: WebSocketRoute) {
  const server = browser.connectToServer()
  return {
    server,
    async disconnect() {
      const options = { code: 1011, reason: 'Test connection loss' }
      await Promise.all([browser.close(options), server.close(options)])
    },
  }
}
