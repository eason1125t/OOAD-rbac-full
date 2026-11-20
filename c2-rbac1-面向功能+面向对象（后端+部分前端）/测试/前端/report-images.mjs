// 文件职责：对本次归档的 JaCoCo 原始 HTML 生成实际页面截图。
// 协作关系：使用本测试工程 Playwright 与本机 Edge，不改写报告内容。
import { chromium } from '@playwright/test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
const root = fileURLToPath(new URL('../结果/', import.meta.url))
const browser = await chromium.launch({ channel: 'msedge' })
try {
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 }, deviceScaleFactor: 1 })
  for (const edition of ['func', 'oo']) {
    await page.goto(pathToFileURL(path.join(root, edition, 'jacoco', 'index.html')).href)
    await page.screenshot({ path: path.join(root, edition, 'JaCoCo结果.png'), fullPage: true })
  }
} finally {
  await browser.close()
}
