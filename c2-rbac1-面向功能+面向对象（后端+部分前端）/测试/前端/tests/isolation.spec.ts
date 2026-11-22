import { apiGet, trackAuth } from './support/auth'
// 两版使用同一浏览器上下文，从页面、Cookie 和数据变化验证隔离。
import { expect, test, type Page } from '@playwright/test'

async function login(page: Page, edition: 'func' | 'oo', username: string) {
  await page.goto(`/${edition}/`)
  trackAuth(page)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill('1234')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText(username, { exact: true })).toBeVisible()
}

test('C2-10 同一浏览器两标签页各自登录、写入和退出', async ({ context }) => {
  const func = await context.newPage()
  const oo = await context.newPage()
  await login(func, 'func', 'finance-employee')
  await login(oo, 'oo', 'company-a-admin')
  const cookies = await context.cookies()
  const funcCookie = cookies.find(cookie => cookie.name === 'RBAC1_FUNC_REFRESH')
  const ooCookie = cookies.find(cookie => cookie.name === 'RBAC1_OO_REFRESH')
  expect(funcCookie?.path).toBe('/func/api/auth/')
  expect(ooCookie?.path).toBe('/oo/api/auth/')
  expect(funcCookie?.value).not.toBe(ooCookie?.value)
  const csrfToken = async (page: Page, edition: string) => {
    const response = await apiGet(page, '/' + edition + '/api/auth/csrf')
    expect(response.ok()).toBeTruthy()
    const { token } = await response.json()
    expect(token).toBeTruthy()
    return token
  }
  expect(await csrfToken(func, 'func')).not.toBe(await csrfToken(oo, 'oo'))

  const path = '/api/business-systems/demo-finance-app/requests?pageSize=1'
  const systemCount = async (page: Page, edition: string) => {
    const response = await apiGet(page, '/' + edition + path)
    expect(response.status()).toBe(200)
    return (await response.json()).total
  }
  const [funcCount, ooCount] = await Promise.all([systemCount(func, 'func'), systemCount(oo, 'oo')])
  await func.getByRole('button', { name: '发起申请', exact: true }).click()
  const dialog = func.getByRole('dialog')
  await dialog.getByRole('textbox', { name: '申请标题' }).fill('版本隔离验收 ' + Date.now())
  await dialog.getByRole('button', { name: '保存申请' }).click()
  await expect(func.getByRole('region', { name: '操作结果' })).toContainText('申请已提交。')
  const detail = func.getByRole('region', { name: '申请详情' })
  await detail.getByRole('button', { name: '审批', exact: true }).click()
  await expect(detail).toContainText('已审批')
  expect(await systemCount(func, 'func')).toBe(funcCount + 1)
  expect(await systemCount(oo, 'oo')).toBe(ooCount)

  await func.getByRole('button', { name: '退出登录' }).click()
  await expect(func.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  expect((await apiGet(func, '/func/api/auth/me')).status()).toBe(401)
  await oo.reload()
  await expect(oo.getByText('company-a-admin', { exact: true })).toBeVisible()
  expect((await context.cookies()).find(cookie => cookie.name === 'RBAC1_OO_REFRESH')?.value.split('.')[0])
    .toBe(ooCookie?.value.split('.')[0])
})
