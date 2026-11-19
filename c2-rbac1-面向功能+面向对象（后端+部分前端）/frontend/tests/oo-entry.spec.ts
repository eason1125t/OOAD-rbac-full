// 验证共用前端按 URL 选择后端并加载独立登录页。
import { expect, test } from '@playwright/test'

test('C2-08 首页进入指定版本', async ({ page }, info) => {
  const name = info.project.name === 'oo' ? '面向对象版' : '面向功能版'
  await page.goto('/')
  await page.getByRole('link', { name: '进入' + name }).click()
  await expect(page).toHaveURL(url => url.pathname === '/' + info.project.name + '/')
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  await expect(page.getByText(name, { exact: false })).toBeVisible()
})
