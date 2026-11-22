import { fileURLToPath } from 'node:url'
import path from 'node:path'
// 文件职责：在同源环境运行真实双版登录验收；与隔离运行器及测试归档协作。
import { defineConfig } from '@playwright/test'

const port = process.env.RBAC_FRONTEND_TEST_PORT || '5173'
const baseURL = `http://127.0.0.1:${port}`
const results = process.env.RBAC_BROWSER_RESULTS_DIR || fileURLToPath(new URL('../结果/', import.meta.url))

export default defineConfig({
  testDir: './tests',
  outputDir: path.join(results, 'browser-artifacts'),
  reporter: [['list'], ['json', { outputFile: path.join(results, 'browser-results.json') }], ['html', { outputFolder: path.join(results, 'browser-report'), open: 'never' }]],
  workers: 1,
  projects: [
    { name: 'oo', testIgnore: '**/isolation.spec.ts' },
    { name: 'func', testIgnore: '**/isolation.spec.ts' },
    { name: 'dual', testMatch: '**/isolation.spec.ts' },
  ],
  use: {
    baseURL,
    channel: process.env.RBAC_BROWSER_CHANNEL || undefined,
    screenshot: 'only-on-failure',
    // Trace会包含Authorization和认证响应；归档仅保留断言、页面截图及非敏感摘要。
    trace: 'off',
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
