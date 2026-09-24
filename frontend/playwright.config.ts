import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/setup.ts',
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['junit', { outputFile: 'test-results/browser.xml', stripANSIControlSequences: true }]],
  // Traces/screenshots can capture credentials and one-time invitations.
  use: { baseURL: 'http://127.0.0.1:15173', browserName: 'chromium', trace: 'off', screenshot: 'off', video: 'off' },
  webServer: {
    command: 'npm run build && npm run preview -- --port 15173', url: 'http://127.0.0.1:15173', reuseExistingServer: false,
    env: { VITE_API_BASE_URL: 'http://127.0.0.1:18080' },
  },
})
