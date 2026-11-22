import { apiGet, trackAuth } from './support/auth'
// 从同一真实浏览器会话分页核对三份原表，日志仅保留摘要。
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { expect, test, type Page } from '@playwright/test'

const input = process.env.RBAC_EXCEL_REVIEW_DIR
const sourceScript = fileURLToPath(new URL('./workbook-fingerprints.py', import.meta.url))
const fields = {
  employees: ['name', 'username', 'sourceBranchName', 'sourceDepartmentName', 'jobTitle', 'phone'],
  departments: ['name', 'managerName', 'sourceParentName'],
  'catalog-entries': ['systemName', 'moduleName', 'featureName', 'description', 'administratorCell',
    'executiveCell', 'departmentManagerCell', 'projectManagerCell', 'employeeCell'],
}
type Sheet = keyof typeof fields
type Entry = Record<string, string | number | boolean | null>
const demoLogins = new Set([
  'super-admin', 'company-a-admin', 'engineering-admin', 'engineering-employee', 'finance-employee',
  'engineering-requester', 'engineering-inherited-approver', 'company-b-employee',
])
const demoDepartments = new Set(['示例工程部', '示例财务部'])

async function collect(page: Page, endpoint: Sheet): Promise<Entry[]> {
  const size = 100
  const get = async (index: number) => {
    const response = await apiGet(page, `/${test.info().project.name}/api/${endpoint}?page=${index}&pageSize=${size}`)
    expect(response.ok(), `${endpoint} 第 ${index} 页读取成功`).toBeTruthy()
    return response.json() as Promise<{ items: Entry[]; total: number }>
  }
  const first = await get(1)
  const rows = [...first.items]
  for (let index = 2; index <= Math.ceil(first.total / size); index += 5) {
    const pages = Array.from({ length: Math.min(5, Math.ceil(first.total / size) - index + 1) },
      (_, offset) => get(index + offset))
    for (const result of await Promise.all(pages)) rows.push(...result.items)
  }
  expect(rows.length).toBe(first.total)
  expect(new Set(rows.map(row => row.id)).size).toBe(first.total)
  return rows
}

function fingerprint(endpoint: Sheet, rows: Entry[]) {
  const encoded = rows.map(row => Buffer.from(JSON.stringify(fields[endpoint].map(field => row[field] ?? null))))
    .sort(Buffer.compare)
  const hash = createHash('sha256')
  for (const [index, value] of encoded.entries()) {
    if (index > 0) hash.update('\n')
    hash.update(value)
  }
  return { rows: rows.length, digest: hash.digest('hex') }
}

test('C2-09 从管理端全量对照员工、部门和功能权限原表', async ({ page }) => {
  test.skip(!input, '请设置 RBAC_EXCEL_REVIEW_DIR 并提前导入完整课程工作簿')
  test.setTimeout(120_000)
  const expected = JSON.parse(execFileSync('python', [sourceScript, input!], { encoding: 'utf8' }))
  await page.goto(`/${test.info().project.name}/`)
  trackAuth(page)
  await page.getByLabel('用户名').fill('super-admin')
  await page.getByLabel('密码').fill('1234')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '管理总览' })).toBeVisible()
  const navigation = page.getByRole('navigation', { name: '管理导航' })

  await navigation.getByRole('link', { name: '员工管理' }).click()
  await expect(page.getByText(`当前范围共 ${expected.employees.rows + demoLogins.size} 条`)).toBeVisible()
  const directory = await collect(page, 'employees')
  expect(new Set(directory.filter(row => demoLogins.has(String(row.username))).map(row => row.username)))
    .toEqual(demoLogins)
  const employees = directory.filter(row => !demoLogins.has(String(row.username)))
  expect(employees.filter(row => row.organizationId === null)).toHaveLength(54)
  expect(fingerprint('employees', employees)).toEqual(expected.employees)

  await navigation.getByRole('link', { name: '部门目录' }).click()
  await expect(page.getByRole('columnheader', { name: '部门经理' })).toBeVisible()
  const departments = (await collect(page, 'departments')).filter(row => !demoDepartments.has(String(row.name)))
  expect(fingerprint('departments', departments)).toEqual(expected.departments)

  await navigation.getByRole('link', { name: '权限管理' }).click()
  await page.getByRole('link', { name: 'Excel 原表清单' }).click()
  await expect(page.getByRole('region', { name: '功能权限清单' })).toBeVisible()
  expect(fingerprint('catalog-entries', await collect(page, 'catalog-entries'))).toEqual(expected['catalog-entries'])
})
