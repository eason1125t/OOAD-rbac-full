// 文件职责：只读验收已切换的本机服务，不启动测试后端或写入原有业务数据。
// 协作关系：独立于隔离库完整回归；结果位置由本轮运行器环境指定。
import { defineConfig } from '@playwright/test'
import path from 'node:path'

const results = process.env.RBAC_BROWSER_RESULTS_DIR
if (!results) throw new Error('Set a new RBAC_BROWSER_RESULTS_DIR for local switch evidence')

export default defineConfig({
  testDir: './本机切换',
  outputDir: path.join(results, 'browser-artifacts'),
  reporter: [['list'], ['json', { outputFile: path.join(results, 'browser-results.json') }],
    ['html', { outputFolder: path.join(results, 'browser-report'), open: 'never' }]],
  workers: 1,
  timeout: 60000,
  projects: [
    { name: 'func', testMatch: '**/pages.spec.ts' },
    { name: 'oo', testMatch: '**/pages.spec.ts' },
    { name: 'dual', testMatch: '**/isolation.spec.ts' },
  ],
  use: { baseURL: 'http://127.0.0.1:5173', channel: 'msedge', trace: 'off', screenshot: 'off' },
})
