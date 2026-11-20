// 对两版运行相同的角色权限页检查，写操作只作用于用例自身创建的测试角色。
import { randomUUID } from 'node:crypto'
import { expect, test, type Page } from '@playwright/test'
import { openRoleDialog } from './support/management'

interface Role { id: number; name: string }
interface Permission { id: number; systemId: number; code: string }
interface BusinessSystem { id: number; code: string; ownerOrganizationId: number }

const prefix = () => '/' + test.info().project.name
const navigation = (page: Page) => page.getByRole('navigation', { name: '管理导航' })
const editor = (page: Page) => page.getByRole('dialog', { name: '管理权限' })
const inherited = (page: Page) => editor(page).getByRole('region', { name: '继承权限' })
const direct = (page: Page) => editor(page).getByRole('region', { name: '直接授予' })
const systemSelect = (page: Page) => editor(page).getByRole('combobox', { name: '业务系统', exact: true })
const actionSelect = (page: Page) => editor(page).getByRole('combobox', { name: '动作', exact: true })
const approval = '工程申请审批'

test.beforeEach(async ({ page }) => {
  await page.goto(prefix() + '/')
  await page.getByLabel('用户名').fill('super-admin')
  await page.getByLabel('密码').fill('1234')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '管理总览' })).toBeVisible()
})

async function get<T>(page: Page, path: string): Promise<T> {
  const response = await page.request.get(prefix() + path)
  expect(response.status(), path).toBe(200)
  return response.json()
}

async function write(page: Page, method: 'POST' | 'PUT' | 'DELETE', path: string, body?: object) {
  const { token } = await get<{ token: string }>(page, '/api/auth/csrf')
  const response = await page.request.fetch(prefix() + path, {
    method, data: body, headers: { 'X-CSRF-TOKEN': token },
  })
  expect(response.status(), `${method} ${path}`).toBe(method === 'POST' ? 201 : 204)
  return response
}

function edge(role: Role, source: Role) {
  return `/api/roles/${role.id}/parents/${source.id}`
}

function grant(role: Role, permission: Permission) {
  return `/api/roles/${role.id}/permissions/${permission.id}`
}

async function engineeringFixture(page: Page, run: (fixture: {
  createRole: (label: string) => Promise<Role>
  permission: Permission
}) => Promise<void>) {
  const systems = await get<BusinessSystem[]>(page, '/api/business-systems')
  const engineering = systems.find(item => item.code === 'demo-engineering-app')!
  expect(engineering).toBeDefined()
  const { items } = await get<{ items: Permission[] }>(page, '/api/permissions?pageSize=100')
  const permission = items.find(item => item.systemId === engineering.id && item.code === 'request:approve')!
  expect(permission).toBeDefined()
  const created: Role[] = []
  const stamp = randomUUID().slice(0, 8)
  const createRole = async (label: string): Promise<Role> => {
    const response = await write(page, 'POST', '/api/roles', {
      name: `权限验收${label} ${stamp}`, ownerOrganizationId: engineering.ownerOrganizationId,
    })
    const role = await response.json() as Role
    created.push(role)
    return role
  }
  try {
    await run({ createRole, permission })
  } finally {
    // 断言失败时也按实际关系清理，避免测试角色积累影响后续分页。
    for (const role of created) {
      const { items: parents } = await get<{ items: Role[] }>(page, `/api/roles/${role.id}/parents`)
      for (const parent of parents) await write(page, 'DELETE', edge(role, parent))
      await write(page, 'DELETE', grant(role, permission))
    }
    for (const role of created) await write(page, 'DELETE', `/api/roles/${role.id}`)
  }
}

test('C2-01 角色权限按系统名称选动作并显示演示继承权限', async ({ page }) => {
  await openRoleDialog(page, '测试工程主管')
  await expect(actionSelect(page)).toBeDisabled()
  await expect(inherited(page).getByRole('listitem')).toHaveCount(1)
  await expect(inherited(page)).toContainText(approval)
  await expect(inherited(page)).toContainText('示例工程申请系统')
  await expect(inherited(page)).toContainText('继承权限，无法修改')
  await expect(inherited(page).getByRole('button', { name: '撤销权限' })).toHaveCount(0)
  await expect(direct(page)).toContainText('暂无直接授予的权限')

  await systemSelect(page).selectOption({ label: '示例工程申请系统' })
  await expect(actionSelect(page).getByRole('option', { name: approval, exact: true })).toHaveCount(1)
  await expect(actionSelect(page).getByRole('option', { name: '公司申请审批', exact: true })).toHaveCount(0)
  await actionSelect(page).selectOption({ label: approval })
  await expect(editor(page).getByRole('button', { name: '授予权限', exact: true })).toBeEnabled()
  await systemSelect(page).selectOption({ label: '示例公司申请系统' })
  await expect(actionSelect(page).getByRole('option', { name: '公司申请审批', exact: true })).toHaveCount(1)
  await expect(actionSelect(page).getByRole('option', { name: approval, exact: true })).toHaveCount(0)
  await expect(editor(page).getByRole('button', { name: '授予权限', exact: true })).toBeDisabled()

  await editor(page).getByRole('button', { name: '关闭' }).click()
  await navigation(page).getByRole('link', { name: '权限管理' }).click()
  await expect(page.getByRole('columnheader', { name: '业务系统' })).toBeVisible()
  await expect(page.getByRole('row').filter({ hasText: approval })).toContainText('示例工程申请系统')
})

test('C2-01 撤销同名直接授权保留继承权限', async ({ page }) => {
  await engineeringFixture(page, async ({ createRole, permission }) => {
    const target = await createRole('主管')
    const source = await createRole('来源')
    await write(page, 'PUT', grant(source, permission))
    await write(page, 'PUT', edge(target, source))
    await openRoleDialog(page, target.name)

    await systemSelect(page).selectOption({ label: '示例工程申请系统' })
    await actionSelect(page).selectOption({ label: approval })
    await editor(page).getByRole('button', { name: '授予权限', exact: true }).click()
    await expect(direct(page).getByRole('listitem')).toHaveCount(1)
    await expect(direct(page)).toContainText(approval)
    await expect(inherited(page)).toContainText(approval)

    await direct(page).getByRole('button', { name: '撤销权限' }).click()
    await expect(direct(page)).toContainText('暂无直接授予的权限')
    await expect(inherited(page)).toContainText(approval)
    const result = await get<{ total: number; items: Permission[] }>(
      page, `/api/roles/${target.id}/inherited-permissions?pageSize=1`,
    )
    expect(result).toMatchObject({ total: 1, items: [expect.objectContaining({ id: permission.id })] })
  })
})

test('C2-02 / C2-04 两个来源的权限去重，最后一条继承边移除后消失', async ({ page }) => {
  await engineeringFixture(page, async ({ createRole, permission }) => {
    const target = await createRole('主管')
    const first = await createRole('来源甲')
    const second = await createRole('来源乙')
    for (const source of [first, second]) {
      await write(page, 'PUT', grant(source, permission))
      await write(page, 'PUT', edge(target, source))
    }

    async function checkInherited(count: number) {
      await openRoleDialog(page, target.name)
      const result = await get<{ total: number; items: Permission[] }>(
        page, `/api/roles/${target.id}/inherited-permissions?pageSize=1`,
      )
      expect(result).toMatchObject({
        total: count,
        items: count ? [expect.objectContaining({ id: permission.id })] : [],
      })
      await expect(inherited(page).getByRole('listitem')).toHaveCount(count)
      if (count === 0) await expect(inherited(page)).toContainText('暂无继承权限')
      await editor(page).getByRole('button', { name: '关闭' }).click()
    }

    async function removeSource(source: Role) {
      await openRoleDialog(page, target.name, '继承管理')
      const dialog = page.getByRole('dialog', { name: '继承管理' })
      const relation = dialog.getByRole('listitem').filter({ hasText: source.name })
      await relation.getByRole('button', { name: '移除继承' }).click()
      await expect(relation).toHaveCount(0)
      await dialog.getByRole('button', { name: '关闭' }).click()
    }

    await checkInherited(1)
    await removeSource(first)
    await checkInherited(1)
    await removeSource(second)
    await checkInherited(0)
  })
})
