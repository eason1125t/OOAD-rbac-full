// 文件职责：直接访问部署入口，复用页面入口与双标签页隔离测试，不启动本地 Vite。
// 协作关系：使用本目录 Playwright 依赖，结果独立归档到测试/结果/部署。
import { defineConfig } from '@playwright/test'

const address = process.env.RBAC_DEPLOY_BASE_URL
if (!address) throw new Error('Set RBAC_DEPLOY_BASE_URL to the public site origin')
const target = new URL(address)
if (!['http:', 'https:'].includes(target.protocol) || target.pathname !== '/' || target.search || target.hash) {
  throw new Error('RBAC_DEPLOY_BASE_URL must be an HTTP(S) origin without a path')
}

export default defineConfig({
  testDir: './tests',
  testMatch: ['**/oo-entry.spec.ts', '**/isolation.spec.ts'],
  outputDir: '../结果/部署/browser-artifacts',
  reporter: [['list'], ['json', { outputFile: '../结果/部署/browser-results.json' }]],
  workers: 1,
  timeout: 60_000,
  projects: [
    { name: 'func', testIgnore: '**/isolation.spec.ts' },
    { name: 'oo', testIgnore: '**/isolation.spec.ts' },
    { name: 'dual', testMatch: '**/isolation.spec.ts' },
  ],
  use: { baseURL: target.origin, channel: process.env.RBAC_BROWSER_CHANNEL || undefined, trace: 'off' },
})
