import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e-docker',
  workers: 1,
  use: {
    baseURL: 'http://localhost:3780',
    trace: 'on-first-retry',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
