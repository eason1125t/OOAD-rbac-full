// 在同源开发环境运行真实双会话验收。
import { defineConfig } from '@playwright/test'

const port = process.env.RBAC_FRONTEND_TEST_PORT || '5173'
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './tests',
  workers: 1,
  use: {
    baseURL,
    channel: process.env.RBAC_BROWSER_CHANNEL || undefined,
  },
  webServer: {
    command: `npm run dev -- --port ${port}`,
    url: baseURL,
    reuseExistingServer: process.env.RBAC_REUSE_FRONTEND === 'true',
  },
})
