# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: jwt.spec.ts >> JWT 切换账号丢弃旧身份在途响应，不在新账号页面展示
- Location: tests\jwt.spec.ts:83:1

# Error details

```
Error: page.goto: Navigation to "http://127.0.0.1:15173/oo/" is interrupted by another navigation to "http://127.0.0.1:15173/oo/"
Call log:
  - navigating to "http://127.0.0.1:15173/oo/", waiting until "load"

```

# Page snapshot

```yaml
- main [ref=f1e3]:
  - paragraph [ref=f1e9]: OOAD RBAC · 面向对象版
  - generic [ref=f1e10]:
    - heading "登录工作台" [level=1] [ref=f1e11]
    - generic [ref=f1e12]:
      - text: 用户名
      - textbox "用户名" [ref=f1e13]
    - generic [ref=f1e14]:
      - text: 密码
      - textbox "密码" [ref=f1e15]
    - button "登录" [ref=f1e16] [cursor=pointer]
```

# Test source

```ts
  1   | // 文件职责：真实浏览器验证内存JWT、同版多标签页协调、恢复、故障及写入重放边界。
  2   | // 协作关系：使用真实隔离后端；明确标记的过期/网络桩只用于触发客户端路径，Redis只读计数确认实际刷新。
  3   | import { execFileSync } from 'node:child_process'
  4   | import { expect, test, type Page } from '@playwright/test'
  5   | import { apiGet, observedBearer, trackAuth } from './support/auth'
  6   | 
  7   | const prefix = () => '/' + test.info().project.name
  8   | const access = (page: Page) => page.getByRole('region', { name: '我的角色与权限' })
  9   | 
  10  | async function login(page: Page, username = 'engineering-requester') {
  11  |   trackAuth(page)
> 12  |   await page.goto(prefix() + '/')
      |              ^ Error: page.goto: Navigation to "http://127.0.0.1:15173/oo/" is interrupted by another navigation to "http://127.0.0.1:15173/oo/"
  13  |   await page.getByLabel('用户名').fill(username)
  14  |   await page.getByLabel('密码').fill('1234')
  15  |   await page.getByRole('button', { name: '登录', exact: true }).click()
  16  |   await expect(page.getByText(username, { exact: true })).toBeVisible()
  17  | }
  18  | 
  19  | /** 仅观察本轮已知sid的摘要数量；不枚举、删除或修改Redis记录，不输出凭证。 */
  20  | function refreshCount(page: Page) {
  21  |   const edition = test.info().project.name
  22  |   const namespace = process.env['RBAC_' + edition.toUpperCase() + '_AUTH_NAMESPACE']
  23  |   if (!namespace?.startsWith('rbac1:' + edition + ':ui-jwt-test:')) throw new Error('Isolated JWT namespace required')
  24  |   const sid = JSON.parse(Buffer.from(observedBearer(page).split('.')[1], 'base64url').toString()).sid
  25  |   if (!/^[a-f0-9-]{36}$/.test(sid)) throw new Error('Invalid test sid')
  26  |   const result = execFileSync('docker', ['exec', 'ooad-rbac-c2-dev-redis', 'redis-cli', '--raw', 'SCARD', namespace + ':{' + sid + '}:used'],
  27  |     { encoding: 'utf8' })
  28  |   return Number(result.trim())
  29  | }
  30  | 
  31  | test('JWT 刷新页面恢复身份，凭证仅在内存与HttpOnly刷新Cookie', async ({ page, context }, info) => {
  32  |   await login(page)
  33  |   await page.reload()
  34  |   await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  35  |   const cookies = await context.cookies()
  36  |   const refresh = cookies.find(cookie => cookie.name === 'RBAC1_' + info.project.name.toUpperCase() + '_REFRESH')
  37  |   expect(refresh?.httpOnly).toBe(true)
  38  |   expect(refresh?.sameSite).toBe('Strict')
  39  |   expect(refresh?.path).toBe(prefix() + '/api/auth/')
  40  |   expect(cookies.some(cookie => cookie.name.endsWith('_SESSION'))).toBe(false)
  41  |   expect(await page.evaluate(() => [...Object.values(localStorage), ...Object.values(sessionStorage)]
  42  |     .some(value => /eyJ[A-Za-z0-9_-]+\./.test(value)))).toBe(false)
  43  |   const result = await apiGet(page, prefix() + '/api/auth/me')
  44  |   expect(result.status()).toBe(200)
  45  |   await page.screenshot({ path: info.outputPath('JWT身份恢复.png'), fullPage: true })
  46  | })
  47  | 
  48  | test('JWT 两标签页并发过期只轮换一次，Cookie入口由共享协调器串行执行', async ({ page, context }) => {
  49  |   await login(page, 'engineering-inherited-approver')
  50  |   const peer = await context.newPage()
  51  |   trackAuth(peer)
  52  |   await peer.goto(page.url())
  53  |   await expect(peer.getByText('engineering-inherited-approver', { exact: true })).toBeVisible()
  54  |   await access(page).getByText('我的角色与权限', { exact: true }).click()
  55  |   await access(peer).getByText('我的角色与权限', { exact: true }).click()
  56  |   const old = observedBearer(page)
  57  |   const before = refreshCount(page)
  58  |   const expire = async (actor: Page) => actor.route('**/api/auth/me/permission-check?*', async route => {
  59  |     if (route.request().headers()['authorization'] === 'Bearer ' + old) {
  60  |       await route.fulfill({ status: 401, contentType: 'application/json', body: JSON.stringify({ code: 'ACCESS_TOKEN_EXPIRED', message: '过期路径测试桩' }) })
  61  |     } else await route.continue()
  62  |   })
  63  |   await expire(page); await expire(peer)
  64  |   await Promise.all([access(page).getByRole('button', { name: '检查前端权限', exact: true }).click(),
  65  |     access(peer).getByRole('button', { name: '检查前端权限', exact: true }).click()])
  66  |   await expect(access(page).getByRole('status')).toContainText('前端权限：允许')
  67  |   await expect(access(peer).getByRole('status')).toContainText('前端权限：允许')
  68  |   expect(refreshCount(page) - before).toBe(1)
  69  | })
  70  | 
  71  | test('JWT 同版退出同步全部标签页，重新打开不能靠旧身份继续', async ({ page, context }) => {
  72  |   await login(page)
  73  |   const peer = await context.newPage()
  74  |   trackAuth(peer)
  75  |   await peer.goto(page.url())
  76  |   await expect(peer.getByText('engineering-requester', { exact: true })).toBeVisible()
  77  |   await page.getByRole('button', { name: '退出登录' }).click()
  78  |   await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  79  |   await peer.reload()
  80  |   await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  81  | })
  82  | 
  83  | test('JWT 切换账号丢弃旧身份在途响应，不在新账号页面展示', async ({ page, context }) => {
  84  |   await login(page, 'engineering-inherited-approver')
  85  |   const peer = await context.newPage()
  86  |   await peer.goto(page.url())
  87  |   await expect(peer.getByText('engineering-inherited-approver', { exact: true })).toBeVisible()
  88  |   await access(page).getByText('我的角色与权限', { exact: true }).click()
  89  |   let release!: () => void
  90  |   const held = new Promise<void>(resolve => { release = resolve })
  91  |   let received!: () => void
  92  |   const requestSeen = new Promise<void>(resolve => { received = resolve })
  93  |   await page.route('**/api/auth/me/permission-check?*', async route => {
  94  |     const response = await route.fetch()
  95  |     received(); await held
  96  |     await route.fulfill({ response })
  97  |   })
  98  |   await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  99  |   await requestSeen
  100 |   await peer.getByRole('button', { name: '退出登录' }).click()
  101 |   await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  102 |   await login(peer, 'finance-employee')
  103 |   await expect(page.getByText('finance-employee', { exact: true })).toBeVisible()
  104 |   release()
  105 |   await expect(page.getByText('engineering-inherited-approver', { exact: true })).toHaveCount(0)
  106 |   await expect(page.getByRole('heading', { name: '示例财务申请系统' })).toBeVisible()
  107 | })
  108 | 
  109 | test('JWT 网络结果未知的写入不自动重放', async ({ page }) => {
  110 |   await login(page)
  111 |   const title = '未知结果不重放 ' + Date.now()
  112 |   let submissions = 0
```