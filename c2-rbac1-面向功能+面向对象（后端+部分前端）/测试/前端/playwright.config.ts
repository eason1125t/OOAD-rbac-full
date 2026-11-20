import { fileURLToPath } from 'node:url'
// 在同源开发环境运行真实双会话验收。
import { defineConfig } from '@playwright/test'

const port = process.env.RBAC_FRONTEND_TEST_PORT || '5173'
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './tests',
  outputDir: '../结果/browser-artifacts',
  reporter: [['list'], ['json', { outputFile: '../结果/browser-results.json' }], ['html', { outputFolder: '../结果/browser-report', open: 'never' }]],
  workers: 1,
  projects: [
    { name: 'oo', testIgnore: '**/isolation.spec.ts' },
    { name: 'func', testIgnore: '**/isolation.spec.ts' },
    { name: 'dual', testMatch: '**/isolation.spec.ts' },
  ],
  use: {
    baseURL,
    channel: process.env.RBAC_BROWSER_CHANNEL || undefined,
  },
  webServer: {
    cwd: fileURLToPath(new URL('../../frontend/', import.meta.url)),
    command: process.env.RBAC_BROWSER_PREVIEW === 'true'
      ? `npm exec -- vite preview --host 127.0.0.1 --strictPort --port ${port}`
      : `npm run dev -- --port ${port}`,
    url: baseURL,
    reuseExistingServer: process.env.RBAC_REUSE_FRONTEND === 'true',
  },
})
