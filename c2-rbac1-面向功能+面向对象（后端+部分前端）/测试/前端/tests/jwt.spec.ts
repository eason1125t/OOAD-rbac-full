// 文件职责：真实浏览器验证内存JWT、同版多标签页协调、恢复、故障及写入重放边界。
// 协作关系：使用真实隔离后端；明确标记的过期/网络桩只用于触发客户端路径，Redis只读计数确认实际刷新。
import { execFileSync } from 'node:child_process'
import { expect, test, type Page } from '@playwright/test'
import { apiGet, observedBearer, trackAuth } from './support/auth'
import { isolateAuthWorkerNetwork } from './support/worker-network'

const prefix = () => '/' + test.info().project.name
const access = (page: Page) => page.getByRole('region', { name: '我的角色与权限' })

async function login(page: Page, username = 'engineering-requester') {
  trackAuth(page)
  await page.goto(prefix() + '/')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill('1234')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByText(username, { exact: true })).toBeVisible()
}

/** 仅观察本轮已知sid的摘要数量；不枚举、删除或修改Redis记录，不输出凭证。 */
function refreshCount(page: Page) {
  const edition = test.info().project.name
  const namespace = process.env['RBAC_' + edition.toUpperCase() + '_AUTH_NAMESPACE']
  if (!namespace?.startsWith('rbac1:' + edition + ':ui-jwt-test:')) throw new Error('Isolated JWT namespace required')
  const sid = JSON.parse(Buffer.from(observedBearer(page).split('.')[1], 'base64url').toString()).sid
  if (!/^[a-f0-9-]{36}$/.test(sid)) throw new Error('Invalid test sid')
  const result = execFileSync('docker', ['exec', 'ooad-rbac-c2-dev-redis', 'redis-cli', '--raw', 'SCARD', namespace + ':{' + sid + '}:used'],
    { encoding: 'utf8' })
  return Number(result.trim())
}

test('JWT 刷新页面恢复身份，凭证仅在内存与HttpOnly刷新Cookie', async ({ page, context }, info) => {
  await login(page)
  await page.reload()
  await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  const cookies = await context.cookies()
  const refresh = cookies.find(cookie => cookie.name === 'RBAC1_' + info.project.name.toUpperCase() + '_REFRESH')
  expect(refresh?.httpOnly).toBe(true)
  expect(refresh?.sameSite).toBe('Strict')
  expect(refresh?.path).toBe(prefix() + '/api/auth/')
  expect(cookies.some(cookie => cookie.name.endsWith('_SESSION'))).toBe(false)
  expect(await page.evaluate(() => [...Object.values(localStorage), ...Object.values(sessionStorage)]
    .some(value => /eyJ[A-Za-z0-9_-]+\./.test(value)))).toBe(false)
  const result = await apiGet(page, prefix() + '/api/auth/me')
  expect(result.status()).toBe(200)
  await page.screenshot({ path: info.outputPath('JWT身份恢复.png'), fullPage: true })
})

test('JWT 两标签页并发过期只轮换一次，Cookie入口由共享协调器串行执行', async ({ page, context }) => {
  await login(page, 'engineering-inherited-approver')
  const peer = await context.newPage()
  trackAuth(peer)
  await peer.goto(page.url())
  await expect(peer.getByText('engineering-inherited-approver', { exact: true })).toBeVisible()
  await access(page).getByText('我的角色与权限', { exact: true }).click()
  await access(peer).getByText('我的角色与权限', { exact: true }).click()
  const old = observedBearer(page)
  const before = refreshCount(page)
  const expire = async (actor: Page) => actor.route('**/api/auth/me/permission-check?*', async route => {
    if (route.request().headers()['authorization'] === 'Bearer ' + old) {
      await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'ACCESS_TOKEN_EXPIRED', message: '过期路径测试桩' }) })
    } else await route.continue()
  })
  await expire(page); await expire(peer)
  await Promise.all([access(page).getByRole('button', { name: '检查前端权限', exact: true }).click(),
    access(peer).getByRole('button', { name: '检查前端权限', exact: true }).click()])
  await expect(access(page).getByRole('status')).toContainText('前端权限：允许')
  await expect(access(peer).getByRole('status')).toContainText('前端权限：允许')
  expect(refreshCount(page) - before).toBe(1)
})

test('JWT 同版退出同步全部标签页，重新打开不能靠旧身份继续', async ({ page, context }) => {
  await login(page)
  const peer = await context.newPage()
  trackAuth(peer)
  await peer.goto(page.url())
  await expect(peer.getByText('engineering-requester', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '退出登录' }).click()
  await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  await peer.reload()
  await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
})

test('JWT 切换账号丢弃旧身份在途响应，不在新账号页面展示', async ({ page, context }) => {
  await login(page, 'engineering-inherited-approver')
  const peer = await context.newPage()
  await peer.goto(page.url())
  await expect(peer.getByText('engineering-inherited-approver', { exact: true })).toBeVisible()
  await access(page).getByText('我的角色与权限', { exact: true }).click()
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  let received!: () => void
  const requestSeen = new Promise<void>(resolve => { received = resolve })
  let completed!: () => void
  const oldRequestDone = new Promise<void>(resolve => { completed = resolve })
  await page.route('**/api/auth/me/permission-check?*', async route => {
    const response = await route.fetch()
    received(); await held
    // 新身份工作区可能整页跳转，旧请求此时已取消；两种路径都必须保持新身份。
    try { await route.fulfill({ response }) } catch { /* 已取消的旧页面不再接收响应。 */ }
    finally { completed() }
  })
  try {
    await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
    await requestSeen
    await peer.getByRole('button', { name: '退出登录' }).click()
    await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
    // 等待退出自身的整页跳转完成，再在现有登录页操作，避免测试制造相互打断的导航。
    await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
    await peer.getByLabel('用户名').fill('finance-employee')
    await peer.getByLabel('密码').fill('1234')
    await peer.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByText('finance-employee', { exact: true })).toBeVisible()
    release()
    await oldRequestDone
    await expect(page.getByText('engineering-inherited-approver', { exact: true })).toHaveCount(0)
    await expect(page.locator('.subtitle')).toHaveText('示例财务申请系统')
  } finally { release() }
})

test('JWT 网络结果未知的写入不自动重放', async ({ page }) => {
  await login(page)
  const title = '未知结果不重放 ' + Date.now()
  let submissions = 0
  await page.route('**/api/business-systems/demo-engineering-app/requests', async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    submissions++
    await route.fetch() // 真实后端已提交，再丢弃响应模拟未知执行结果。
    await route.abort('failed')
  })
  await page.getByRole('button', { name: '发起申请', exact: true }).click()
  await page.getByRole('dialog').getByLabel('申请标题').fill(title)
  await page.getByRole('dialog').getByRole('button', { name: '保存申请' }).click()
  await expect(page.getByRole('alert')).toContainText('无法连接后端')
  expect(submissions).toBe(1)
  const result = await (await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests?pageSize=100')).json()
  expect(result.items.filter((item: { title: string }) => item.title === title)).toHaveLength(1)
})

test('JWT 只有确定的过期允许写入重试一次，后端只创建一条', async ({ page }) => {
  await login(page)
  const old = observedBearer(page)
  const before = refreshCount(page)
  const title = '过期后单次重试 ' + Date.now()
  let attempts = 0
  await page.route('**/api/business-systems/demo-engineering-app/requests', async route => {
    if (route.request().method() !== 'POST') { await route.continue(); return }
    attempts++
    if (route.request().headers()['authorization'] === 'Bearer ' + old) {
      await route.fulfill({ status: 401, contentType: 'application/json', body: '{"code":"ACCESS_TOKEN_EXPIRED","message":"过期路径测试桩"}' })
    } else await route.continue()
  })
  await page.getByRole('button', { name: '发起申请', exact: true }).click()
  await page.getByRole('dialog').getByLabel('申请标题').fill(title)
  await page.getByRole('dialog').getByRole('button', { name: '保存申请' }).click()
  await expect(page.getByRole('region', { name: '申请详情' })).toContainText(title)
  expect(attempts).toBe(2)
  expect(refreshCount(page) - before).toBe(1)
  const result = await (await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests?pageSize=100')).json()
  expect(result.items.filter((item: { title: string }) => item.title === title)).toHaveLength(1)
})

test('JWT 普通403不刷新凭证，也不清除已登录身份', async ({ page }) => {
  await login(page)
  await access(page).getByText('我的角色与权限', { exact: true }).click()
  const before = refreshCount(page)
  await page.route('**/api/auth/me/permission-check?*', route => route.fulfill({ status: 403,
    contentType: 'application/json', body: '{"code":"PERMISSION_DENIED","message":"拒绝路径测试桩"}' }))
  await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  await expect(access(page).getByRole('alert')).toContainText('拒绝路径测试桩')
  expect(refreshCount(page)).toBe(before)
  await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
})

test('JWT 刷新网络故障保留Cookie并停止自动重试，恢复网络后可显式重新登录', async ({ page, context, browser }, info) => {
  await login(page)
  await access(page).getByText('我的角色与权限', { exact: true }).click()
  const cookieName = 'RBAC1_' + info.project.name.toUpperCase() + '_REFRESH'
  const cookie = (await context.cookies()).find(item => item.name === cookieName)!.value
  const before = refreshCount(page)
  const network = await isolateAuthWorkerNetwork(browser)
  let businessAttempts = 0
  await page.route('**/api/auth/me/permission-check?*', route => {
    businessAttempts++
    return route.fulfill({ status: 401, contentType: 'application/json',
      body: '{"code":"ACCESS_TOKEN_EXPIRED","message":"过期路径测试桩"}' })
  })
  try {
    await network.setOffline(true)
    await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
    await expect(access(page).getByRole('alert')).toContainText('无法连接后端')
    await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
    expect((await context.cookies()).find(item => item.name === cookieName)!.value === cookie).toBe(true)
    await network.setOffline(false)
    await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
    await expect(access(page).getByRole('alert')).toContainText('无法连接后端')
    expect(businessAttempts).toBe(2)
    expect(refreshCount(page)).toBe(before)
    // 调试连接会保活旧worker；恢复正常生命周期后，再验证真实退出与整页登录恢复。
    await network.close()
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
    await page.getByLabel('用户名').fill('engineering-requester')
    await page.getByLabel('密码').fill('1234')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  } finally { await network.setOffline(false); await network.close() }
})
