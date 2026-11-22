# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: authorization.spec.ts >> C2-04 / C2-11 演示申请人和继承审批人分工，乙公司无法进入工程系统
- Location: tests\authorization.spec.ts:105:1

# Error details

```
Error: page.goto: Navigation to "http://127.0.0.1:15173/oo/" is interrupted by another navigation to "http://127.0.0.1:15173/oo/"
Call log:
  - navigating to "http://127.0.0.1:15173/oo/", waiting until "load"

```

# Page snapshot

```yaml
- main [ref=f3e3]:
  - paragraph [ref=f3e9]: OOAD RBAC · 面向对象版
  - generic [ref=f3e10]:
    - heading "登录工作台" [level=1] [ref=f3e11]
    - generic [ref=f3e12]:
      - text: 用户名
      - textbox "用户名" [ref=f3e13]
    - generic [ref=f3e14]:
      - text: 密码
      - textbox "密码" [ref=f3e15]
    - button "登录" [ref=f3e16] [cursor=pointer]
```

# Test source

```ts
  1   | import { apiFetch, apiGet, trackAuth } from './support/auth'
  2   | // 用同一组浏览器测试验收两版的业务操作与权限管理，真实会话之间相互隔离。
  3   | import { expect, test, type Page } from '@playwright/test'
  4   | import { openRoleDialog, selectInheritanceSource, selectRegrantRole } from './support/management'
  5   | 
  6   | const demoPassword = '1234'
  7   | const prefix = () => '/' + test.info().project.name
  8   | const cookieName = () => test.info().project.name === 'oo' ? 'RBAC1_OO_REFRESH' : 'RBAC1_FUNC_REFRESH'
  9   | const requestPanel = (page: Page) => page.getByRole('region', { name: '申请详情' })
  10  | const receipt = (page: Page) => page.getByRole('region', { name: '操作结果' })
  11  | 
  12  | async function login(page: Page, username: string) {
> 13  |   await page.goto(prefix() + '/')
      |              ^ Error: page.goto: Navigation to "http://127.0.0.1:15173/oo/" is interrupted by another navigation to "http://127.0.0.1:15173/oo/"
  14  |   trackAuth(page)
  15  |   await page.getByLabel('用户名').fill(username)
  16  |   await page.getByLabel('密码').fill(demoPassword)
  17  |   await page.getByRole('button', { name: '登录', exact: true }).click()
  18  |   await expect(page.getByText(username, { exact: true })).toBeVisible()
  19  | }
  20  | 
  21  | async function logout(page: Page) {
  22  |   await page.getByRole('button', { name: '退出登录' }).click()
  23  |   await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  24  | }
  25  | 
  26  | async function create(page: Page, title: string) {
  27  |   await page.getByRole('button', { name: '发起申请', exact: true }).click()
  28  |   const dialog = page.getByRole('dialog')
  29  |   await dialog.getByLabel('申请标题').fill(title)
  30  |   await dialog.getByRole('button', { name: '保存申请' }).click()
  31  |   await expect(requestPanel(page)).toContainText(title)
  32  |   await expect(receipt(page)).toContainText('申请已提交。')
  33  | }
  34  | 
  35  | async function writeWithSession(page: Page, method: 'POST' | 'PUT' | 'DELETE', path: string, data?: object) {
  36  |   const csrf = await apiGet(page, prefix() + '/api/auth/csrf')
  37  |   expect(csrf.ok()).toBeTruthy()
  38  |   const response = await apiFetch(page, prefix() + path, {
  39  |     method, data, headers: { 'X-CSRF-TOKEN': (await csrf.json()).token },
  40  |   })
  41  |   expect(response.ok(), `${method} ${path} ${response.status()}`).toBeTruthy()
  42  |   return response
  43  | }
  44  | 
  45  | test('C2-11 至 C2-14 三个系统完成申请操作并回查状态', async ({ browser, baseURL }, info) => {
  46  |   const context = await browser.newContext({ baseURL })
  47  |   try {
  48  |     const page = await context.newPage()
  49  |     await login(page, 'engineering-employee')
  50  |     await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-engineering-app/')
  51  |     const title = `工程验收 ${Date.now()}`
  52  |     await create(page, title)
  53  |     await requestPanel(page).getByRole('button', { name: '修改', exact: true }).click()
  54  |     const editor = page.getByRole('dialog')
  55  |     await editor.getByLabel('申请标题').fill(`${title} 已修改`)
  56  |     await editor.getByRole('button', { name: '保存申请' }).click()
  57  |     await expect(requestPanel(page)).toContainText('已修改')
  58  |     await requestPanel(page).getByRole('button', { name: '撤回', exact: true }).click()
  59  |     await expect(requestPanel(page)).toContainText('已撤回')
  60  |     await expect(requestPanel(page).getByRole('button', { name: '审批', exact: true })).toHaveCount(0)
  61  |     await expect(page.getByRole('row').filter({ hasText: title })).toContainText('已撤回')
  62  |     const refreshed = page.waitForResponse(response => response.ok() && response.request().method() === 'GET' &&
  63  |       response.url().includes('/api/business-systems/demo-engineering-app/requests?'))
  64  |     await page.getByRole('button', { name: '刷新' }).click()
  65  |     await refreshed
  66  |     await expect(page.getByRole('row').filter({ hasText: title })).toContainText('已撤回')
  67  |     await logout(page)
  68  | 
  69  |     await login(page, 'company-a-admin')
  70  |     await page.getByRole('combobox', { name: '当前系统' }).selectOption('/business/demo-company-app/')
  71  |     await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-company-app/')
  72  |     const rejectedTitle = `公司验收 ${Date.now()}`
  73  |     await create(page, rejectedTitle)
  74  |     await requestPanel(page).getByRole('button', { name: '驳回', exact: true }).click()
  75  |     await expect(requestPanel(page)).toContainText('已驳回')
  76  |     await requestPanel(page).getByRole('button', { name: '删除', exact: true }).click()
  77  |     await page.getByRole('dialog').getByRole('button', { name: '确认删除' }).click()
  78  |     await expect(receipt(page)).toContainText('申请已删除。')
  79  |     await expect(page.getByRole('row').filter({ hasText: rejectedTitle })).toHaveCount(0)
  80  |     await logout(page)
  81  | 
  82  |     await login(page, 'finance-employee')
  83  |     await expect(page).toHaveURL(url => url.pathname === prefix() + '/business/demo-finance-app/')
  84  |     await expect(page.getByRole('combobox', { name: '当前系统' }).getByRole('option', {
  85  |       name: '示例工程申请系统',
  86  |     })).toHaveCount(0)
  87  |     const denied = await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests')
  88  |     expect(denied.status()).toBe(403)
  89  |     await create(page, `财务验收 ${Date.now()}`)
  90  |     await requestPanel(page).getByRole('button', { name: '审批', exact: true }).click()
  91  |     await expect(receipt(page)).toContainText('申请已审批。')
  92  |     await expect(requestPanel(page)).toContainText('已审批')
  93  |     await page.screenshot({ path: info.outputPath('business.png'), fullPage: true })
  94  |     await page.setViewportSize({ width: 390, height: 844 })
  95  |     expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  96  |     const tableWidth = await page.locator('.request-panel .table-scroll').evaluate(node =>
  97  |       [node.scrollWidth, node.clientWidth])
  98  |     expect(tableWidth[0]).toBeLessThanOrEqual(tableWidth[1])
  99  |     await page.screenshot({ path: info.outputPath('business-mobile.png'), fullPage: true })
  100 |   } finally {
  101 |     await context.close()
  102 |   }
  103 | })
  104 | 
  105 | test('C2-04 / C2-11 演示申请人和继承审批人分工，乙公司无法进入工程系统', async ({ page }) => {
  106 |   const collection = '/api/business-systems/demo-engineering-app/requests'
  107 |   async function allowedActions() {
  108 |     const response = await apiGet(page, prefix() + collection + '?pageSize=1')
  109 |     expect(response.status()).toBe(200)
  110 |     return (await response.json()).allowedActions.sort()
  111 |   }
  112 | 
  113 |   await login(page, 'engineering-requester')
```