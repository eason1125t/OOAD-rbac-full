// 文件职责：对本次归档的 JaCoCo 原始 HTML 生成实际页面截图。
// 协作关系：使用本测试工程 Playwright 与本机 Edge；可选参数指定归档批次，不改写报告内容。
import { chromium } from '@playwright/test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import path from 'node:path'
import { existsSync } from 'node:fs'
const root = process.argv[2] ? path.resolve(process.argv[2]) : fileURLToPath(new URL('../结果/', import.meta.url))
const browser = await chromium.launch({ channel: 'msedge' })
try {
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 }, deviceScaleFactor: 1 })
  for (const edition of ['func', 'oo']) {
    const directory = path.join(root, existsSync(path.join(root, edition)) ? edition : '后端-' + edition)
    await page.goto(pathToFileURL(path.join(directory, 'jacoco', 'index.html')).href)
    await page.screenshot({ path: path.join(directory, 'JaCoCo结果.png'), fullPage: true })
  }
} finally {
  await browser.close()
}
