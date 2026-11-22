# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: jwt.spec.ts >> JWT 刷新网络故障保留Cookie并停止自动重试，恢复网络后可显式重新登录
- Location: tests\jwt.spec.ts:174:1

# Error details

```
Error: expect(locator).toContainText(expected) failed

Locator: getByRole('region', { name: '我的角色与权限' }).getByRole('alert')
Expected substring: "无法连接后端"
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toContainText" getByRole('region', { name: '我的角色与权限' }).getByRole('alert') with timeout 5000ms
  - waiting for getByRole('region', { name: '我的角色与权限' }).getByRole('alert')

```

```yaml
- main:
  - paragraph: OOAD RBAC · 面向功能版
  - heading "登录工作台" [level=1]
  - text: 用户名
  - textbox "用户名"
  - text: 密码
  - textbox "密码"
  - alert: 登录已失效，请重新登录。
  - button "登录"
```

# Test source

```ts
  89  |   let release!: () => void
  90  |   const held = new Promise<void>(resolve => { release = resolve })
  91  |   let received!: () => void
  92  |   const requestSeen = new Promise<void>(resolve => { received = resolve })
  93  |   let completed!: () => void
  94  |   const oldRequestDone = new Promise<void>(resolve => { completed = resolve })
  95  |   await page.route('**/api/auth/me/permission-check?*', async route => {
  96  |     const response = await route.fetch()
  97  |     received(); await held
  98  |     // 新身份工作区可能整页跳转，旧请求此时已取消；两种路径都必须保持新身份。
  99  |     try { await route.fulfill({ response }) } catch { /* 已取消的旧页面不再接收响应。 */ }
  100 |     finally { completed() }
  101 |   })
  102 |   try {
  103 |     await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  104 |     await requestSeen
  105 |     await peer.getByRole('button', { name: '退出登录' }).click()
  106 |     await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  107 |     // 等待退出自身的整页跳转完成，再在现有登录页操作，避免测试制造相互打断的导航。
  108 |     await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  109 |     await peer.getByLabel('用户名').fill('finance-employee')
  110 |     await peer.getByLabel('密码').fill('1234')
  111 |     await peer.getByRole('button', { name: '登录', exact: true }).click()
  112 |     await expect(page.getByText('finance-employee', { exact: true })).toBeVisible()
  113 |     release()
  114 |     await oldRequestDone
  115 |     await expect(page.getByText('engineering-inherited-approver', { exact: true })).toHaveCount(0)
  116 |     await expect(page.locator('.subtitle')).toHaveText('示例财务申请系统')
  117 |   } finally { release() }
  118 | })
  119 | 
  120 | test('JWT 网络结果未知的写入不自动重放', async ({ page }) => {
  121 |   await login(page)
  122 |   const title = '未知结果不重放 ' + Date.now()
  123 |   let submissions = 0
  124 |   await page.route('**/api/business-systems/demo-engineering-app/requests', async route => {
  125 |     if (route.request().method() !== 'POST') { await route.continue(); return }
  126 |     submissions++
  127 |     await route.fetch() // 真实后端已提交，再丢弃响应模拟未知执行结果。
  128 |     await route.abort('failed')
  129 |   })
  130 |   await page.getByRole('button', { name: '发起申请', exact: true }).click()
  131 |   await page.getByRole('dialog').getByLabel('申请标题').fill(title)
  132 |   await page.getByRole('dialog').getByRole('button', { name: '保存申请' }).click()
  133 |   await expect(page.getByRole('alert')).toContainText('无法连接后端')
  134 |   expect(submissions).toBe(1)
  135 |   const result = await (await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests?pageSize=100')).json()
  136 |   expect(result.items.filter((item: { title: string }) => item.title === title)).toHaveLength(1)
  137 | })
  138 | 
  139 | test('JWT 只有确定的过期允许写入重试一次，后端只创建一条', async ({ page }) => {
  140 |   await login(page)
  141 |   const old = observedBearer(page)
  142 |   const before = refreshCount(page)
  143 |   const title = '过期后单次重试 ' + Date.now()
  144 |   let attempts = 0
  145 |   await page.route('**/api/business-systems/demo-engineering-app/requests', async route => {
  146 |     if (route.request().method() !== 'POST') { await route.continue(); return }
  147 |     attempts++
  148 |     if (route.request().headers()['authorization'] === 'Bearer ' + old) {
  149 |       await route.fulfill({ status: 401, contentType: 'application/json', body: '{"code":"ACCESS_TOKEN_EXPIRED","message":"过期路径测试桩"}' })
  150 |     } else await route.continue()
  151 |   })
  152 |   await page.getByRole('button', { name: '发起申请', exact: true }).click()
  153 |   await page.getByRole('dialog').getByLabel('申请标题').fill(title)
  154 |   await page.getByRole('dialog').getByRole('button', { name: '保存申请' }).click()
  155 |   await expect(page.getByRole('region', { name: '申请详情' })).toContainText(title)
  156 |   expect(attempts).toBe(2)
  157 |   expect(refreshCount(page) - before).toBe(1)
  158 |   const result = await (await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests?pageSize=100')).json()
  159 |   expect(result.items.filter((item: { title: string }) => item.title === title)).toHaveLength(1)
  160 | })
  161 | 
  162 | test('JWT 普通403不刷新凭证，也不清除已登录身份', async ({ page }) => {
  163 |   await login(page)
  164 |   await access(page).getByText('我的角色与权限', { exact: true }).click()
  165 |   const before = refreshCount(page)
  166 |   await page.route('**/api/auth/me/permission-check?*', route => route.fulfill({ status: 403,
  167 |     contentType: 'application/json', body: '{"code":"PERMISSION_DENIED","message":"拒绝路径测试桩"}' }))
  168 |   await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  169 |   await expect(access(page).getByRole('alert')).toContainText('拒绝路径测试桩')
  170 |   expect(refreshCount(page)).toBe(before)
  171 |   await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  172 | })
  173 | 
  174 | test('JWT 刷新网络故障保留Cookie并停止自动重试，恢复网络后可显式重新登录', async ({ page, context }, info) => {
  175 |   await login(page)
  176 |   await access(page).getByText('我的角色与权限', { exact: true }).click()
  177 |   const cookieName = 'RBAC1_' + info.project.name.toUpperCase() + '_REFRESH'
  178 |   const cookie = (await context.cookies()).find(item => item.name === cookieName)!.value
  179 |   const before = refreshCount(page)
  180 |   let businessAttempts = 0
  181 |   await page.route('**/api/auth/me/permission-check?*', route => {
  182 |     businessAttempts++
  183 |     return route.fulfill({ status: 401, contentType: 'application/json',
  184 |       body: '{"code":"ACCESS_TOKEN_EXPIRED","message":"过期路径测试桩"}' })
  185 |   })
  186 |   try {
  187 |     await context.setOffline(true)
  188 |     await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
> 189 |     await expect(access(page).getByRole('alert')).toContainText('无法连接后端')
      |                                                   ^ Error: expect(locator).toContainText(expected) failed
  190 |     await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  191 |     expect((await context.cookies()).find(item => item.name === cookieName)!.value === cookie).toBe(true)
  192 |     await context.setOffline(false)
  193 |     await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  194 |     await expect(access(page).getByRole('alert')).toContainText('无法连接后端')
  195 |     expect(businessAttempts).toBe(2)
  196 |     expect(refreshCount(page)).toBe(before)
  197 |     await page.getByRole('button', { name: '退出登录' }).click()
  198 |     await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  199 |     await page.getByLabel('用户名').fill('engineering-requester')
  200 |     await page.getByLabel('密码').fill('1234')
  201 |     await page.getByRole('button', { name: '登录', exact: true }).click()
  202 |     await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  203 |   } finally { await context.setOffline(false) }
  204 | })
  205 | 
```