import { apiFetch, apiGet, trackAuth } from './support/auth'
// 文件职责：以同一组真实浏览器场景验证两版身份面板与部门员工查询。
// 协作关系：连接本轮隔离测试后端；仅故障展示用例使用明确标注的HTTP桩。
import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'

const prefix = () => '/' + test.info().project.name
const access = (page: Page) => page.getByRole('region', { name: '我的角色与权限' })
const directory = (page: Page) => page.getByRole('dialog', { name: '部门与员工', exact: true })
const members = (page: Page) => directory(page).getByRole('region', { name: '部门员工列表' })
async function login(page: Page, username: string) {
  await page.goto(prefix() + '/')
  trackAuth(page)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill('1234')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText(username, { exact: true })).toBeVisible()
}
async function openDirectory(page: Page) {
  await page.getByRole('button', { name: '部门与员工', exact: true }).click()
  await expect(directory(page)).toBeVisible()
  await expect(members(page).getByRole('table')).toBeVisible()
}
async function write(page: Page, method: 'POST' | 'DELETE', path: string, data?: object) {
  const csrf = await apiGet(page, prefix() + '/api/auth/csrf')
  expect(csrf.status()).toBe(200)
  const result = await apiFetch(page, prefix() + path, {
    method, data, headers: { 'X-CSRF-TOKEN': (await csrf.json()).token },
  })
  expect(result.status()).toBe(method === 'POST' ? 201 : 204)
  return result
}

test('ID-S6 继承角色、有效权限、单项及API检查接入当前系统', async ({ page }, info) => {
  await login(page, 'engineering-inherited-approver')
  await access(page).getByText('我的角色与权限', { exact: true }).click()
  await expect(access(page)).toContainText('继承自')
  await expect(access(page)).toContainText('示例继承审批来源')
  await expect(access(page)).toContainText('request:approve')
  await expect(page.getByRole('button', { name: '发起申请', exact: true })).toHaveCount(0)
  await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  await expect(access(page).getByRole('status')).toContainText('前端权限：允许')
  await access(page).getByRole('button', { name: '检查 API 权限', exact: true }).click()
  await expect(access(page).getByRole('status')).toContainText('API 权限：允许')
  await access(page).getByLabel('业务操作').selectOption('createRequest')
  await expect(access(page).getByRole('status')).toHaveCount(0)
  await access(page).getByRole('button', { name: '检查 API 权限', exact: true }).click()
  await expect(access(page).getByRole('status')).toContainText('API 权限：不允许')
  await access(page).getByLabel('前端权限码').fill('unknown:permission')
  await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  await expect(access(page).getByRole('alert')).toContainText('权限不存在')
  await expect(access(page).getByRole('status')).toHaveCount(0)
  await access(page).getByLabel('前端权限码').fill('request:approve')
  await access(page).getByLabel('业务操作').selectOption('approveRequest')
  await access(page).getByRole('button', { name: '检查 API 权限', exact: true }).click()
  await expect(access(page).getByRole('status')).toContainText('API 权限：允许')
  await page.screenshot({ path: info.outputPath('当前身份权限.png'), fullPage: true })
})

test('ID-S6 普通员工只见本部门，搜索及详情边界由后端执行', async ({ page }) => {
  await login(page, 'engineering-requester')
  await openDirectory(page)
  const tree = directory(page).getByRole('region', { name: '部门树' })
  await expect(tree.getByRole('button', { name: '示例工程部', exact: true })).toBeVisible()
  await expect(tree).not.toContainText('示例财务部')
  await expect(tree.getByRole('button', { name: '展开', exact: true })).toHaveCount(0)
  await tree.getByRole('button', { name: '示例工程部', exact: true }).click()
  const responsePromise = page.waitForResponse(response => response.url().includes('/api/employees?')
    && response.url().includes('q=engineering-requester'))
  await directory(page).getByLabel('姓名或账号').fill('engineering-requester')
  await directory(page).getByRole('button', { name: '查找员工' }).click()
  const data = await (await responsePromise).json()
  expect(data.total).toBe(1)
  expect(Object.keys(data.items[0]).sort()).toEqual(['id', 'name', 'organizationId', 'username'])
  await expect(members(page).getByRole('row')).toHaveCount(2)
  await expect(members(page).getByRole('button', { name: '查看资料' })).toHaveCount(0)
  expect((await apiGet(page, prefix() + '/api/employees/' + data.items[0].id)).status()).toBe(403)
  await directory(page).getByLabel('姓名或账号').fill('finance-employee')
  await directory(page).getByRole('button', { name: '查找员工' }).click()
  await expect(members(page)).toContainText('没有匹配的员工')
})

test('ID-S6 公司管理员逐层展开部门并查看范围内员工资料', async ({ page }, info) => {
  await login(page, 'company-a-admin')
  await openDirectory(page)
  const tree = directory(page).getByRole('region', { name: '部门树' })
  await expect(tree).not.toContainText('示例乙公司')
  await tree.getByRole('button', { name: '展开示例甲公司', exact: true }).click()
  await tree.getByRole('button', { name: '示例工程部', exact: true }).click()
  await directory(page).getByLabel('姓名或账号').fill('engineering-requester')
  await directory(page).getByRole('button', { name: '查找员工' }).click()
  await members(page).getByRole('button', { name: '查看资料' }).click()
  await expect(members(page).getByRole('region', { name: '员工资料' })).toContainText('engineering-requester')
  await page.screenshot({ path: info.outputPath('部门树与员工资料.png'), fullPage: true })
  await tree.getByRole('button', { name: '返回根部门' }).click()
  await expect(tree.getByRole('button', { name: '展开示例甲公司', exact: true })).toBeVisible()
  await directory(page).getByLabel('部门名称或编码').fill('财务')
  await tree.getByRole('button', { name: '查找部门' }).click()
  await expect(tree.getByRole('button', { name: '示例财务部', exact: true })).toBeVisible()
  await expect(tree.getByRole('button', { name: '示例工程部', exact: true })).toHaveCount(0)
})

test('ID-S6 员工分页和搜索重置页码，普通用户不接收联系字段', async ({ browser, baseURL }) => {
  const adminContext = await browser.newContext({ baseURL })
  const workerContext = await browser.newContext({ baseURL })
  const admin = await adminContext.newPage()
  const worker = await workerContext.newPage()
  const ids: number[] = []
  try {
    await login(admin, 'super-admin')
    const systems = await (await apiGet(admin, prefix() + '/api/business-systems')).json()
    const owner = systems.find((item: { code: string }) => item.code === 'demo-engineering-app').ownerOrganizationId
    const tag = 'ui-' + randomUUID()
    for (let index = 0; index < 12; index++) {
      const response = await write(admin, 'POST', '/api/employees', {
        username: tag + '-' + index, name: tag + '-' + index, organizationId: owner,
        phone: '13812345678', jobTitle: '测试岗位',
      })
      ids.push((await response.json()).id)
    }
    await login(worker, 'engineering-requester')
    await openDirectory(worker)
    await directory(worker).getByLabel('姓名或账号').fill(tag)
    await directory(worker).getByRole('button', { name: '查找员工' }).click()
    await expect(members(worker)).toContainText('共 12 人')
    await expect(members(worker).getByRole('row')).toHaveCount(11)
    await members(worker).getByRole('button', { name: '下一员工页' }).click()
    await expect(members(worker)).toContainText('员工第 2 页')
    await expect(members(worker).getByRole('row')).toHaveCount(3)
    await directory(worker).getByLabel('姓名或账号').fill(tag + '-11')
    await directory(worker).getByRole('button', { name: '查找员工' }).click()
    await expect(members(worker)).toContainText('员工第 1 页 / 共 1 人')
    await expect(members(worker)).not.toContainText('13812345678')
  } finally {
    for (const id of ids) await write(admin, 'DELETE', '/api/employees/' + id)
    await adminContext.close()
    await workerContext.close()
  }
})

test('ID-S6 权限与目录请求失败时清除旧数据并允许重试（HTTP故障桩）', async ({ page }) => {
  await login(page, 'engineering-inherited-approver')
  await access(page).getByText('我的角色与权限', { exact: true }).click()
  await access(page).getByRole('button', { name: '检查 API 权限', exact: true }).click()
  await expect(access(page).getByRole('status')).toContainText('API 权限：允许')
  await page.route('**/api/auth/me/api-check?*', route => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'AUTHORIZATION_UNAVAILABLE', message: '授权数据暂不可用' }),
  }), { times: 1 })
  await access(page).getByRole('button', { name: '检查 API 权限', exact: true }).click()
  await expect(access(page).getByRole('alert')).toContainText('授权数据暂不可用')
  await expect(access(page).getByRole('status')).toHaveCount(0)
  await access(page).getByRole('button', { name: '检查 API 权限', exact: true }).click()
  await expect(access(page).getByRole('status')).toContainText('API 权限：允许')
  await openDirectory(page)
  await page.route('**/api/employees?*', route => route.fulfill({
    status: 403, contentType: 'application/json', body: JSON.stringify({ code: 'PERMISSION_DENIED', message: '无权查看该组织的数据' }),
  }), { times: 1 })
  await members(page).getByRole('button', { name: '刷新员工' }).click()
  await expect(members(page).getByRole('table')).toHaveCount(0)
  await expect(members(page).getByRole('alert')).toContainText('无权查看')
  await members(page).getByRole('button', { name: '刷新员工' }).click()
  await expect(members(page).getByRole('table')).toBeVisible()
  await directory(page).getByRole('button', { name: '关闭目录' }).click()
  await page.route('**/api/auth/me?systemCode=*', route => route.fulfill({
    status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'AUTHORIZATION_UNAVAILABLE', message: '授权数据暂不可用' }),
  }), { times: 1 })
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('授权数据暂不可用')
  await expect(access(page)).toHaveCount(0)
  await expect(page.getByRole('row')).toHaveCount(0)
  await page.getByRole('button', { name: '刷新', exact: true }).click()
  await expect(access(page)).toBeVisible()
})

test('ID-S6 小屏目录可滚动，切换业务系统不沿用前一系统授权', async ({ page }, info) => {
  await login(page, 'company-a-admin')
  await page.getByRole('combobox', { name: '当前系统' }).selectOption('/business/demo-engineering-app/')
  await expect(access(page)).toContainText('demo-engineering-app')
  await page.getByRole('combobox', { name: '当前系统' }).selectOption('/business/demo-company-app/')
  await expect(access(page)).toContainText('demo-company-app')
  await expect(access(page)).not.toContainText('demo-engineering-app')
  await page.setViewportSize({ width: 390, height: 844 })
  await openDirectory(page)
  await expect(directory(page)).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBeTruthy()
  expect(await directory(page).evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBeTruthy()
  await page.screenshot({ path: info.outputPath('小屏部门目录.png'), fullPage: true })
  await directory(page).getByRole('button', { name: '关闭目录' }).click()
  await expect(directory(page)).toHaveCount(0)
})
