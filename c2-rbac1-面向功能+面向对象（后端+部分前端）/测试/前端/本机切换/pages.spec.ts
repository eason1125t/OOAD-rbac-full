// 文件职责：在原有数据库上只读验证JWT页面恢复、权限与目录，不修改申请或授权。
// 协作关系：使用现有5173网关；只截取合成账号的权限面板，不归档员工原表行。
import { expect, test } from '@playwright/test'

test('本机JWT切换：页面恢复、Bearer、权限与目录读取', async ({ page, context }, info) => {
  const edition = info.project.name
  let bearerObserved = false
  const businessWrites: string[] = []
  page.on('request', request => {
    if (request.url().includes('/api/auth/me') && request.headers()['authorization']?.startsWith('Bearer ')) bearerObserved = true
    if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()) && request.url().includes('/api/')
      && !/\/api\/auth\/(login|refresh|logout)$/.test(request.url())) businessWrites.push(request.method())
  })
  await page.goto('/' + edition + '/')
  await page.getByLabel('用户名').fill('super-admin')
  await page.getByLabel('密码').fill('1234')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '管理总览' })).toBeVisible()
  await page.reload()
  await expect(page.getByText('super-admin', { exact: true })).toBeVisible()
  expect(bearerObserved).toBe(true)
  const cookies = await context.cookies()
  const refresh = cookies.find(cookie => cookie.name === 'RBAC1_' + edition.toUpperCase() + '_REFRESH')
  expect(refresh?.path).toBe('/' + edition + '/api/auth/')
  expect(refresh?.httpOnly).toBe(true)
  expect(cookies.some(cookie => cookie.name.endsWith('_SESSION'))).toBe(false)
  expect(await page.evaluate(() => [...Object.values(localStorage), ...Object.values(sessionStorage)]
    .some(value => /eyJ[A-Za-z0-9_-]+\./.test(value)))).toBe(false)
  await page.getByRole('combobox', { name: '当前系统' }).selectOption('/business/demo-engineering-app/')
  const access = page.getByRole('region', { name: '我的角色与权限' })
  await expect(access).toBeVisible()
  await access.getByText('我的角色与权限', { exact: true }).click()
  await access.getByRole('button', { name: '检查前端权限', exact: true }).click()
  await expect(access.getByRole('status')).toContainText('前端权限：')
  await access.getByRole('button', { name: '检查 API 权限', exact: true }).click()
  await expect(access.getByRole('status')).toContainText('API 权限：')
  await access.screenshot({ path: info.outputPath('当前权限只读验证.png') })
  await page.getByRole('button', { name: '部门与员工', exact: true }).click()
  const directory = page.getByRole('dialog', { name: '部门与员工', exact: true })
  await expect(directory.getByRole('region', { name: '部门员工列表' }).getByRole('table')).toBeVisible()
  await directory.getByLabel('姓名或账号').fill('super-admin')
  await directory.getByRole('button', { name: '查找员工' }).click()
  await expect(directory.getByRole('region', { name: '部门员工列表' }).getByRole('row')).toHaveCount(2)
  await directory.getByRole('button', { name: '关闭目录' }).click()
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  expect(businessWrites).toHaveLength(0)
})
