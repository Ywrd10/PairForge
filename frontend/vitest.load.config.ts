import { defineConfig } from 'vitest/config'

export default defineConfig({ test: {
  environment: 'node', include: ['tools/load/**/*.test.ts'],
  reporters: ['default', 'junit'], outputFile: { junit: 'test-results/load.xml' },
} })
