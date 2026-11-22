import { apiFetch, apiGet, trackAuth } from './support/auth'
// 用同一组浏览器测试验收两版的业务操作与权限管理，真实会话之间相互隔离。
import { expect, test, type Page } from '@playwright/test'
import { openRoleDialog, selectInheritanceSource, selectRegrantRole } from './support/management'

const demoPassword = '1234'
const prefix = () => '/' + test.info().project.name
const cookieName = () => test.info().project.name === 'oo' ? 'RBAC1_OO_REFRESH' : 'RBAC1_FUNC_REFRESH'
const requestPanel = (page: Page) => page.getByRole('region', { name: '申请详情' })
const receipt = (page: Page) => page.getByRole('region', { name: '操作结果' })

async function login(page: Page, username: string) {
  await page.goto(prefix() + '/')
  trackAuth(page)
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(demoPassword)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText(username, { exact: true })).toBeVisible()
}

async function logout(page: Page) {
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
}

async function create(page: Page, title: string) {
  await page.getByRole('button', { name: '发起申请', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('申请标题').fill(title)
  await dialog.getByRole('button', { name: '保存申请' }).click()
  await expect(requestPanel(page)).toContainText(title)
  await expect(receipt(page)).toContainText('申请已提交。')
}

async function writeWithSession(page: Page, method: 'POST' | 'PUT' | 'DELETE', path: string, data?: object) {
  const csrf = await apiGet(page, prefix() + '/api/auth/csrf')
  expect(csrf.ok()).toBeTruthy()
  const response = await apiFetch(page, prefix() + path, {
    method, data, headers: { 'X-CSRF-TOKEN': (await csrf.json()).token },
  })
  expect(response.ok(), `${method} ${path} ${response.status()}`).toBeTruthy()
  return response
}

test('C2-11 至 C2-14 三个系统完成申请操作并回查状态', async ({ browser, baseURL }, info) => {
  const context = await browser.newContext({ baseURL })
  try {
    const page = await context.newPage()
    await login(page, 'engineering-employee')
    await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-engineering-app/')
    const title = `工程验收 ${Date.now()}`
    await create(page, title)
    await requestPanel(page).getByRole('button', { name: '修改', exact: true }).click()
    const editor = page.getByRole('dialog')
    await editor.getByLabel('申请标题').fill(`${title} 已修改`)
    await editor.getByRole('button', { name: '保存申请' }).click()
    await expect(requestPanel(page)).toContainText('已修改')
    await requestPanel(page).getByRole('button', { name: '撤回', exact: true }).click()
    await expect(requestPanel(page)).toContainText('已撤回')
    await expect(requestPanel(page).getByRole('button', { name: '审批', exact: true })).toHaveCount(0)
    await expect(page.getByRole('row').filter({ hasText: title })).toContainText('已撤回')
    const refreshed = page.waitForResponse(response => response.ok() && response.request().method() === 'GET' &&
      response.url().includes('/api/business-systems/demo-engineering-app/requests?'))
    await page.getByRole('button', { name: '刷新' }).click()
    await refreshed
    await expect(page.getByRole('row').filter({ hasText: title })).toContainText('已撤回')
    await logout(page)

    await login(page, 'company-a-admin')
    await page.getByRole('combobox', { name: '当前系统' }).selectOption('/business/demo-company-app/')
    await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-company-app/')
    const rejectedTitle = `公司验收 ${Date.now()}`
    await create(page, rejectedTitle)
    await requestPanel(page).getByRole('button', { name: '驳回', exact: true }).click()
    await expect(requestPanel(page)).toContainText('已驳回')
    await requestPanel(page).getByRole('button', { name: '删除', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '确认删除' }).click()
    await expect(receipt(page)).toContainText('申请已删除。')
    await expect(page.getByRole('row').filter({ hasText: rejectedTitle })).toHaveCount(0)
    await logout(page)

    await login(page, 'finance-employee')
    await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-finance-app/')
    await expect(page.getByRole('combobox', { name: '当前系统' }).getByRole('option', {
      name: '示例工程申请系统',
    })).toHaveCount(0)
    const denied = await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests')
    expect(denied.status()).toBe(403)
    await create(page, `财务验收 ${Date.now()}`)
    await requestPanel(page).getByRole('button', { name: '审批', exact: true }).click()
    await expect(receipt(page)).toContainText('申请已审批。')
    await expect(requestPanel(page)).toContainText('已审批')
    await page.screenshot({ path: info.outputPath('business.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    const tableWidth = await page.locator('.request-panel .table-scroll').evaluate(node =>
      [node.scrollWidth, node.clientWidth])
    expect(tableWidth[0]).toBeLessThanOrEqual(tableWidth[1])
    await page.screenshot({ path: info.outputPath('business-mobile.png'), fullPage: true })
  } finally {
    await context.close()
  }
})

test('C2-04 / C2-11 演示申请人和继承审批人分工，乙公司无法进入工程系统', async ({ page }) => {
  const collection = '/api/business-systems/demo-engineering-app/requests'
  async function allowedActions() {
    const response = await apiGet(page, prefix() + collection + '?pageSize=1')
    expect(response.status()).toBe(200)
    return (await response.json()).allowedActions.sort()
  }

  await login(page, 'engineering-requester')
  await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-engineering-app/')
  expect(await allowedActions()).toEqual(['request:create', 'request:delete', 'request:update', 'request:withdraw'])
  const title = `申请人与审批人分工 ${Date.now()}`
  const created = page.waitForResponse(response => response.request().method() === 'POST' &&
    response.url().endsWith(collection))
  await create(page, title)
  const { id } = await (await created).json()
  await expect(requestPanel(page).getByRole('button', { name: '审批', exact: true })).toHaveCount(0)
  await logout(page)

  await login(page, 'engineering-inherited-approver')
  await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-engineering-app/')
  expect(await allowedActions()).toEqual(['request:approve'])
  await expect(page.getByRole('button', { name: '发起申请', exact: true })).toHaveCount(0)
  const approval = await writeWithSession(page, 'POST', `/api/requests/${id}/approve`)
  expect(await approval.json()).toMatchObject({ requestId: id, status: 'APPROVED' })
  await logout(page)

  await login(page, 'company-b-employee')
  await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-company-b-app/')
  await expect(page.getByRole('combobox', { name: '当前系统' }).getByRole('option', {
    name: '示例工程申请系统',
  })).toHaveCount(0)
  const denied = await apiGet(page, prefix() + collection)
  expect(denied.status()).toBe(403)
  expect(await denied.json()).toMatchObject({ code: 'SYSTEM_ACCESS_DENIED' })
})

test('C2-03 环路被拒绝后可继续维护该角色的继承关系', async ({ page }) => {
  await login(page, 'super-admin')
  const systems = await (await apiGet(page, prefix() + '/api/business-systems')).json()
  const engineering = systems.find((system: { code: string }) => system.code === 'demo-engineering-app')
  expect(engineering).toBeTruthy()
  const stamp = Date.now()
  const roles: { id: number; name: string }[] = []
  try {
    for (const label of ['主管', '中间角色', '审批员']) {
      const response = await writeWithSession(page, 'POST', '/api/roles', {
        name: `环路验收${label} ${stamp}`, ownerOrganizationId: engineering.ownerOrganizationId,
      })
      roles.push(await response.json())
    }
    const [senior, middle, junior] = roles
    await writeWithSession(page, 'PUT', `/api/roles/${senior.id}/parents/${middle.id}`)
    await page.getByRole('navigation', { name: '管理导航' }).getByRole('link', { name: '角色管理' }).click()
    await openRoleDialog(page, middle.name, '继承管理')
    const inheritance = page.getByRole('dialog', { name: '继承管理' })
    const parent = inheritance.getByLabel('继承自')
    await selectInheritanceSource(page, senior.name)
    const conflict = page.waitForResponse(response => response.request().method() === 'PUT' &&
      response.url().endsWith(`/api/roles/${middle.id}/parents/${senior.id}`))
    await inheritance.getByRole('button', { name: '添加继承' }).click()
    const response = await conflict
    expect(response.status()).toBe(409)
    expect(await response.json()).toMatchObject({ code: 'ROLE_INHERITANCE_CYCLE' })
    await expect(inheritance.getByRole('alert')).toContainText('角色继承关系不能形成环路')
    await expect(inheritance).toContainText('暂无继承关系')
    await expect(parent).toBeEnabled()
    await selectInheritanceSource(page, junior.name)
    await inheritance.getByRole('button', { name: '添加继承' }).click()
    await expect(inheritance.getByRole('listitem')).toHaveCount(1)
    await expect(inheritance.getByRole('listitem')).toContainText(junior.name)
    const original = await apiGet(page, prefix() + `/api/roles/${senior.id}/parents`)
    expect(original.ok()).toBeTruthy()
    expect((await original.json()).items).toEqual([expect.objectContaining({ id: middle.id })])
  } finally {
    if (roles.length >= 2) await writeWithSession(page, 'DELETE', `/api/roles/${roles[0].id}/parents/${roles[1].id}`)
    if (roles.length === 3) await writeWithSession(page, 'DELETE', `/api/roles/${roles[1].id}/parents/${roles[2].id}`)
    for (const role of roles) await writeWithSession(page, 'DELETE', `/api/roles/${role.id}`)
  }
})

test('C2-05 共享边仅全局管理员可维护，部门管理员无法查询外部角色', async ({ browser, baseURL, page: admin }) => {
  const localContext = await browser.newContext({ baseURL })
  const created: number[] = []
  try {
    await login(admin, 'super-admin')
    const local = await localContext.newPage()
    await login(local, 'engineering-admin')
    const systems = await (await apiGet(admin, prefix() + '/api/business-systems')).json()
    const other = systems.find((system: { code: string }) => system.code === 'demo-company-b-app')
    expect(other).toBeTruthy()
    for (const owner of [null, null, other.ownerOrganizationId]) {
      const role = await writeWithSession(admin, 'POST', '/api/roles', {
        name: `范围验收 ${Date.now()} ${created.length}`, ownerOrganizationId: owner,
      })
      created.push((await role.json()).id)
    }
    const [senior, source, outside] = created
    const parents = `/api/roles/${senior}/parents`
    await writeWithSession(admin, 'PUT', `${parents}/${source}`)
    const localCsrf = await (await apiGet(local, prefix() + '/api/auth/csrf')).json()
    const deleteAttempt = await apiFetch(local, prefix() + `${parents}/${source}`, { method: 'DELETE',
      headers: { 'X-CSRF-TOKEN': localCsrf.token },
    })
    expect(deleteAttempt.status()).toBe(403)
    expect(await deleteAttempt.json()).toMatchObject({ code: 'PERMISSION_DENIED' })
    const outsideQuery = await apiGet(local, prefix() + `/api/roles/${outside}/parents`)
    expect(outsideQuery.status()).toBe(403)
    expect(await outsideQuery.json()).toMatchObject({ code: 'PERMISSION_DENIED' })
    const globalQuery = await apiGet(admin, prefix() + parents)
    expect(globalQuery.status()).toBe(200)
    expect((await globalQuery.json()).items).toEqual([expect.objectContaining({ id: source })])
  } finally {
    if (created.length >= 2) await writeWithSession(admin, 'DELETE', `/api/roles/${created[0]}/parents/${created[1]}`)
    for (const id of created) await writeWithSession(admin, 'DELETE', `/api/roles/${id}`)
    await localContext.close()
  }
})

test('C2-01、C2-02、C2-04、C2-06 直接与传递继承、权限去重及逐条撤权', async ({ browser, baseURL }, info) => {
  const adminContext = await browser.newContext({ baseURL })
  const workerContext = await browser.newContext({ baseURL })
  try {
    const admin = await adminContext.newPage()
    const worker = await workerContext.newPage()
    await login(admin, 'super-admin')
    await login(worker, 'engineering-admin')
    const systemRows = await (await apiGet(admin, prefix() + '/api/business-systems')).json()
    const app = systemRows.find((row: { code: string }) => row.code === 'demo-engineering-app')
    const operators = (await (await apiGet(admin, prefix() + '/api/employees?pageSize=100')).json()).items
    const employee = operators.find((row: { username: string }) => row.username === 'engineering-admin')
    expect(employee).toBeTruthy()
    const definitions = (await (await apiGet(admin, prefix() + '/api/permissions?pageSize=100')).json()).items
    const approval = definitions.find((row: { code: string; systemId: number }) =>
      row.code === 'request:approve' && row.systemId === app.id)
    const policyPath = `/api/organizations/${app.ownerOrganizationId}/business-systems/demo-engineering-app/permissions/request:approve`
    const initialPolicy = await (await apiGet(admin, prefix() + policyPath)).json()
    if (initialPolicy.status === 'DISABLED') await writeWithSession(admin, 'DELETE', `${policyPath}/disabled`)
    const suffix = Date.now()
    const seniorName = `继承验收主管 ${suffix}`
    const middleName = `继承验收中间角色 ${suffix}`
    const juniorName = `继承验收来源 ${suffix}`
    const senior = await (await writeWithSession(admin, 'POST', '/api/roles',
      { name: seniorName, ownerOrganizationId: app.ownerOrganizationId })).json()
    const middle = await (await writeWithSession(admin, 'POST', '/api/roles',
      { name: middleName, ownerOrganizationId: app.ownerOrganizationId })).json()
    const junior = await (await writeWithSession(admin, 'POST', '/api/roles',
      { name: juniorName, ownerOrganizationId: app.ownerOrganizationId })).json()
    await writeWithSession(admin, 'PUT', `/api/employees/${employee.id}/roles/${senior.id}`)
    await writeWithSession(admin, 'PUT', `/api/roles/${junior.id}/permissions/${approval.id}`)
    if (initialPolicy.status !== 'OPEN') {
      await writeWithSession(admin, 'PUT', `${policyPath}/regrants/${junior.id}`)
    }
    await writeWithSession(admin, 'PUT', `/api/roles/${middle.id}/parents/${junior.id}`)
    const navigation = admin.getByRole('navigation', { name: '管理导航' })
    await navigation.getByRole('link', { name: '角色管理' }).click()
    await openRoleDialog(admin, seniorName, '继承管理')
    const inheritance = admin.getByRole('dialog', { name: '继承管理' })
    await selectInheritanceSource(admin, middleName)
    await inheritance.getByRole('button', { name: '添加继承' }).click()
    await expect(inheritance.getByText('已添加继承')).toBeVisible()
    await expect(inheritance.getByRole('listitem')).toHaveCount(1)
    await expect(inheritance.getByRole('listitem')).toContainText(middleName)
    await inheritance.getByRole('button', { name: '关闭' }).click()

    async function checkEffectiveApproval(count: number) {
      await navigation.getByRole('link', { name: '员工管理' }).click()
      await admin.getByRole('row').filter({ hasText: 'engineering-admin' })
        .getByRole('button', { name: '有效权限' }).click()
      const effective = admin.getByRole('dialog', { name: '员工有效权限' })
      await effective.getByLabel('业务系统').selectOption('demo-engineering-app')
      const result = admin.waitForResponse(response => response.ok() &&
        response.url().includes(`/api/employees/${employee.id}/effective-permissions?`))
      await effective.getByRole('button', { name: '查询权限' }).click()
      const { items } = await (await result).json()
      expect(items.filter((permission: { code: string }) => permission.code === 'request:approve'))
        .toEqual(count ? [expect.objectContaining({ id: approval.id })] : [])
      await expect(effective.getByText('request:approve', { exact: true })).toHaveCount(count)
      await effective.getByRole('button', { name: '关闭' }).click()
    }
    async function checkInheritedApproval() {
      const result = await apiGet(admin, prefix() + `/api/roles/${senior.id}/inherited-permissions?pageSize=1`)
      expect(result.status()).toBe(200)
      expect(await result.json()).toMatchObject({
        total: 1,
        items: [expect.objectContaining({ id: approval.id })],
      })
    }
    await checkInheritedApproval()
    await checkEffectiveApproval(1)

    await navigation.getByRole('link', { name: '角色管理' }).click()
    await openRoleDialog(admin, seniorName, '继承管理')
    await selectInheritanceSource(admin, juniorName)
    await inheritance.getByRole('button', { name: '添加继承' }).click()
    await expect(inheritance.getByRole('listitem')).toHaveCount(2)
    await admin.screenshot({ path: info.outputPath('inheritance.png') })
    await inheritance.getByRole('button', { name: '关闭' }).click()
    await checkInheritedApproval()
    await checkEffectiveApproval(1)

    const collection = '/api/business-systems/demo-engineering-app/requests'
    const approvedTitle = `继承审批验收 ${suffix}`
    const fallbackTitle = `传递路径审批验收 ${suffix}`
    const revokedTitle = `实时撤权验收 ${suffix}`
    await writeWithSession(admin, 'POST', collection, { title: approvedTitle })
    await writeWithSession(admin, 'POST', collection, { title: fallbackTitle })
    await writeWithSession(admin, 'POST', collection, { title: revokedTitle })
    const total = (await (await apiGet(admin, prefix() + collection + '?pageSize=1')).json()).total
    await worker.getByRole('combobox', { name: '当前系统' }).selectOption('/business/demo-engineering-app/')
    let page = 1
    async function goTo(index: number) {
      while (page < Math.ceil(index / 12)) {
        await worker.getByRole('button', { name: '下一页' }).click()
        page++
        await expect(worker.getByText(`第 ${page} 页`, { exact: true })).toBeVisible()
      }
    }
    const employeeCookie = (await workerContext.cookies()).find(cookie => cookie.name === cookieName())
    expect(employeeCookie?.path).toBe(prefix() + '/api/auth/')
    await goTo(total - 2)
    await worker.getByRole('row').filter({ hasText: approvedTitle })
      .getByRole('button', { name: '审批', exact: true }).click()
    await expect(receipt(worker)).toContainText('申请已审批。')
    await navigation.getByRole('link', { name: '角色管理' }).click()
    await openRoleDialog(admin, seniorName, '继承管理')
    await inheritance.getByRole('listitem').filter({ hasText: juniorName })
      .getByRole('button', { name: '移除继承' }).click()
    await expect(inheritance.getByRole('listitem')).toHaveCount(1)
    await expect(inheritance.getByRole('listitem')).toContainText(middleName)
    await inheritance.getByRole('button', { name: '关闭' }).click()
    await checkEffectiveApproval(1)
    await goTo(total - 1)
    const fallback = worker.getByRole('row').filter({ hasText: fallbackTitle })
    await fallback.getByRole('button', { name: '审批', exact: true }).click()
    await expect(fallback).toContainText('已审批')
    await goTo(total)
    const pending = worker.getByRole('row').filter({ hasText: revokedTitle })

    await navigation.getByRole('link', { name: '权限管理' }).click()
    await admin.getByRole('row').filter({ hasText: '工程申请审批' }).getByRole('button', { name: '组织策略' }).click()
    const policy = admin.getByRole('dialog', { name: '组织策略' })
    await policy.getByRole('combobox', { name: '目标组织' }).selectOption({ label: '示例工程部（部门）' })
    await policy.getByRole('button', { name: '停用', exact: true }).click()
    await policy.getByRole('button', { name: '确认停用' }).click()
    await expect(policy.getByRole('status').filter({ hasText: '已停用' })).toBeVisible()
    await pending.first().getByRole('button', { name: '审批', exact: true }).click()
    await expect(receipt(worker)).toContainText('未执行')
    await policy.getByRole('button', { name: '重新开放' }).click()
    await selectRegrantRole(admin, juniorName)
    await policy.getByRole('button', { name: '重授角色' }).click()
    await expect(policy.getByText('已重授 1 个角色')).toBeVisible()
    await policy.getByRole('button', { name: '关闭' }).click()
    await worker.getByRole('button', { name: '刷新' }).click()
    await expect(pending.first().getByRole('button', { name: '审批', exact: true })).toBeVisible()

    await navigation.getByRole('link', { name: '角色管理' }).click()
    await openRoleDialog(admin, seniorName, '继承管理')
    await inheritance.getByRole('button', { name: '移除继承' }).click()
    await expect(inheritance.getByText('已移除继承')).toBeVisible()
    const rejected = worker.waitForResponse(response => response.request().method() === 'POST' &&
      response.url().endsWith('/approve'))
    await pending.first().getByRole('button', { name: '审批', exact: true }).click()
    expect((await rejected).status()).toBe(403)
    await expect(receipt(worker)).toContainText('鉴权结果')
    await expect(receipt(worker)).toContainText('拒绝')
    await expect(pending.first()).toContainText('待审批')
    expect((await workerContext.cookies()).find(cookie => cookie.name === cookieName())?.value.split('.')[0])
      .toBe(employeeCookie?.value.split('.')[0])
    await worker.screenshot({ path: info.outputPath('revocation.png'), fullPage: true })
    await inheritance.getByRole('button', { name: '关闭' }).click()
    await checkEffectiveApproval(0)

    // 恢复工程员工演示权限，测试角色只存在于隔离的浏览器验收库。
    const roles = (await (await apiGet(admin, prefix() + '/api/roles?pageSize=100')).json()).items
    const originalSources = roles.filter((row: { ownerOrganizationId: number; name: string }) =>
      row.ownerOrganizationId === app.ownerOrganizationId &&
      ['示例工程审批员', '示例申请经办员', '示例继承审批来源'].includes(row.name))
    for (const role of originalSources) await writeWithSession(admin, 'PUT', `${policyPath}/regrants/${role.id}`)
    await writeWithSession(admin, 'DELETE', `/api/employees/${employee.id}/roles/${senior.id}`)
    await writeWithSession(admin, 'DELETE', `/api/roles/${middle.id}/parents/${junior.id}`)
    await writeWithSession(admin, 'DELETE', `/api/roles/${junior.id}/permissions/${approval.id}`)
    await writeWithSession(admin, 'DELETE', `/api/roles/${senior.id}`)
    await writeWithSession(admin, 'DELETE', `/api/roles/${middle.id}`)
    await writeWithSession(admin, 'DELETE', `/api/roles/${junior.id}`)
  } finally {
    await adminContext.close()
    await workerContext.close()
  }
})
