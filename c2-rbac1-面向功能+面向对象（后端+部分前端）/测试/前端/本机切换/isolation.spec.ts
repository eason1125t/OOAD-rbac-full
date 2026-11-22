// 文件职责：只读验证已切换服务的同版退出同步与两版独立登录，不创建或处理申请。
// 协作关系：复用真实前端SharedWorker及刷新Cookie；上下文结束后不保留凭证。
import { expect, test, type Page } from '@playwright/test'

async function login(page: Page, edition: string, username: string) {
  await page.goto('/' + edition + '/')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill('1234')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText(username, { exact: true })).toBeVisible()
}

test('本机JWT切换：同版退出同步，另一版刷新后仍保持登录', async ({ context }) => {
  const func = await context.newPage()
  const peer = await context.newPage()
  const oo = await context.newPage()
  await login(func, 'func', 'engineering-requester')
  await peer.goto(func.url())
  await expect(peer.getByText('engineering-requester', { exact: true })).toBeVisible()
  await login(oo, 'oo', 'finance-employee')
  const cookies = await context.cookies()
  expect(cookies.some(cookie => cookie.name === 'RBAC1_FUNC_REFRESH')).toBe(true)
  expect(cookies.some(cookie => cookie.name === 'RBAC1_OO_REFRESH')).toBe(true)
  await func.getByRole('button', { name: '退出登录' }).click()
  await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  await oo.reload()
  await expect(oo.getByText('finance-employee', { exact: true })).toBeVisible()
  await oo.getByRole('button', { name: '退出登录' }).click()
  await expect(oo.getByRole('heading', { name: '登录工作台' })).toBeVisible()
})
