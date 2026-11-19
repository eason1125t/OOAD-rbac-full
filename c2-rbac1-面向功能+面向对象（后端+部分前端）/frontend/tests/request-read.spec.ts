// 用真实的两版会话验证查看权撤销后页面清除缓存内容，最后恢复演示授权。
import { expect, test, type Page } from '@playwright/test'

test('C2-15 撤销查看权后原页面清空列表和详情，重授后恢复读取', async ({ browser, baseURL }, info) => {
  const prefix = '/' + info.project.name
  const adminContext = await browser.newContext({ baseURL })
  const workerContext = await browser.newContext({ baseURL })
  let roleId: number | undefined
  let permissionId: number | undefined
  const admin = await adminContext.newPage()

  async function login(page: Page, username: string) {
    await page.goto(prefix + '/')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill('1234')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByText(username, { exact: true })).toBeVisible()
  }

  async function grant(method: 'PUT' | 'DELETE') {
    const response = await admin.request.get(prefix + '/api/auth/csrf')
    expect(response.status()).toBe(200)
    const { token } = await response.json()
    const result = await admin.request.fetch(prefix + `/api/roles/${roleId}/permissions/${permissionId}`, {
      method, headers: { 'X-CSRF-TOKEN': token },
    })
    expect(result.status()).toBe(204)
  }

  try {
    await login(admin, 'super-admin')
    const systems = await (await admin.request.get(prefix + '/api/business-systems')).json()
    const app = systems.find((row: { code: string }) => row.code === 'demo-engineering-app')
    const roles = await (await admin.request.get(prefix + '/api/roles?pageSize=100')).json()
    roleId = roles.items.find((row: { name: string; ownerOrganizationId: number }) =>
      row.name === '示例申请查看员' && row.ownerOrganizationId === app.ownerOrganizationId)?.id
    const definitions = await (await admin.request.get(prefix + '/api/permissions?pageSize=100')).json()
    permissionId = definitions.items.find((row: { code: string; systemId: number }) =>
      row.code === 'request:read' && row.systemId === app.id)?.id
    expect(roleId).toBeDefined()
    expect(permissionId).toBeDefined()

    const worker = await workerContext.newPage()
    await login(worker, 'engineering-requester')
    await expect(worker.getByRole('button', { name: '发起申请', exact: true })).toBeVisible()
    await worker.getByRole('button', { name: '发起申请', exact: true }).click()
    const dialog = worker.getByRole('dialog')
    const title = '查看撤权验收 ' + Date.now()
    await dialog.getByLabel('申请标题').fill(title)
    await dialog.getByRole('button', { name: '保存申请' }).click()
    await expect(worker.getByRole('region', { name: '申请详情' })).toContainText(title)
    const cookie = (await workerContext.cookies()).find(item =>
      item.name === (info.project.name === 'func' ? 'RBAC1_FUNC_SESSION' : 'RBAC1_OO_SESSION'))

    await grant('DELETE')
    const rejected = worker.waitForResponse(response => response.request().method() === 'GET' &&
      response.url().includes('/api/business-systems/demo-engineering-app/requests?'))
    await worker.getByRole('button', { name: '刷新', exact: true }).click()
    expect((await rejected).status()).toBe(403)
    await expect(worker.getByRole('alert')).toContainText('权限')
    await expect(worker.getByRole('row')).toHaveCount(0)
    await expect(worker.getByRole('region', { name: '申请详情' })).toHaveCount(0)
    await expect(worker.getByRole('button', { name: '发起申请', exact: true })).toHaveCount(0)
    expect((await workerContext.cookies()).find(item => item.name === cookie?.name)?.value).toBe(cookie?.value)

    await grant('PUT')
    await worker.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(worker.getByRole('button', { name: '发起申请', exact: true })).toBeVisible()
    await expect(worker.getByRole('alert')).toHaveCount(0)
  } finally {
    if (roleId != null && permissionId != null) await grant('PUT')
    await adminContext.close()
    await workerContext.close()
  }
})
