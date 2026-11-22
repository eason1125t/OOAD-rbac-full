# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: jwt.spec.ts >> JWT 刷新网络故障保留Cookie并停止自动重试，恢复网络后可显式重新登录
- Location: tests\jwt.spec.ts:175:1

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByText('engineering-requester', { exact: true })
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" getByText('engineering-requester', { exact: true }) with timeout 5000ms
  - waiting for getByText('engineering-requester', { exact: true })

```

```yaml
- status: 正在读取登录状态…
```

# Test source

```ts
  104 |     await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  105 |     await requestSeen
  106 |     await peer.getByRole('button', { name: '退出登录' }).click()
  107 |     await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  108 |     // 等待退出自身的整页跳转完成，再在现有登录页操作，避免测试制造相互打断的导航。
  109 |     await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  110 |     await peer.getByLabel('用户名').fill('finance-employee')
  111 |     await peer.getByLabel('密码').fill('1234')
  112 |     await peer.getByRole('button', { name: '登录', exact: true }).click()
  113 |     await expect(page.getByText('finance-employee', { exact: true })).toBeVisible()
  114 |     release()
  115 |     await oldRequestDone
  116 |     await expect(page.getByText('engineering-inherited-approver', { exact: true })).toHaveCount(0)
  117 |     await expect(page.locator('.subtitle')).toHaveText('示例财务申请系统')
  118 |   } finally { release() }
  119 | })
  120 | 
  121 | test('JWT 网络结果未知的写入不自动重放', async ({ page }) => {
  122 |   await login(page)
  123 |   const title = '未知结果不重放 ' + Date.now()
  124 |   let submissions = 0
  125 |   await page.route('**/api/business-systems/demo-engineering-app/requests', async route => {
  126 |     if (route.request().method() !== 'POST') { await route.continue(); return }
  127 |     submissions++
  128 |     await route.fetch() // 真实后端已提交，再丢弃响应模拟未知执行结果。
  129 |     await route.abort('failed')
  130 |   })
  131 |   await page.getByRole('button', { name: '发起申请', exact: true }).click()
  132 |   await page.getByRole('dialog').getByLabel('申请标题').fill(title)
  133 |   await page.getByRole('dialog').getByRole('button', { name: '保存申请' }).click()
  134 |   await expect(page.getByRole('alert')).toContainText('无法连接后端')
  135 |   expect(submissions).toBe(1)
  136 |   const result = await (await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests?pageSize=100')).json()
  137 |   expect(result.items.filter((item: { title: string }) => item.title === title)).toHaveLength(1)
  138 | })
  139 | 
  140 | test('JWT 只有确定的过期允许写入重试一次，后端只创建一条', async ({ page }) => {
  141 |   await login(page)
  142 |   const old = observedBearer(page)
  143 |   const before = refreshCount(page)
  144 |   const title = '过期后单次重试 ' + Date.now()
  145 |   let attempts = 0
  146 |   await page.route('**/api/business-systems/demo-engineering-app/requests', async route => {
  147 |     if (route.request().method() !== 'POST') { await route.continue(); return }
  148 |     attempts++
  149 |     if (route.request().headers()['authorization'] === 'Bearer ' + old) {
  150 |       await route.fulfill({ status: 401, contentType: 'application/json', body: '{"code":"ACCESS_TOKEN_EXPIRED","message":"过期路径测试桩"}' })
  151 |     } else await route.continue()
  152 |   })
  153 |   await page.getByRole('button', { name: '发起申请', exact: true }).click()
  154 |   await page.getByRole('dialog').getByLabel('申请标题').fill(title)
  155 |   await page.getByRole('dialog').getByRole('button', { name: '保存申请' }).click()
  156 |   await expect(page.getByRole('region', { name: '申请详情' })).toContainText(title)
  157 |   expect(attempts).toBe(2)
  158 |   expect(refreshCount(page) - before).toBe(1)
  159 |   const result = await (await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests?pageSize=100')).json()
  160 |   expect(result.items.filter((item: { title: string }) => item.title === title)).toHaveLength(1)
  161 | })
  162 | 
  163 | test('JWT 普通403不刷新凭证，也不清除已登录身份', async ({ page }) => {
  164 |   await login(page)
  165 |   await access(page).getByText('我的角色与权限', { exact: true }).click()
  166 |   const before = refreshCount(page)
  167 |   await page.route('**/api/auth/me/permission-check?*', route => route.fulfill({ status: 403,
  168 |     contentType: 'application/json', body: '{"code":"PERMISSION_DENIED","message":"拒绝路径测试桩"}' }))
  169 |   await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  170 |   await expect(access(page).getByRole('alert')).toContainText('拒绝路径测试桩')
  171 |   expect(refreshCount(page)).toBe(before)
  172 |   await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  173 | })
  174 | 
  175 | test('JWT 刷新网络故障保留Cookie并停止自动重试，恢复网络后可显式重新登录', async ({ page, context, browser }, info) => {
  176 |   await login(page)
  177 |   await access(page).getByText('我的角色与权限', { exact: true }).click()
  178 |   const cookieName = 'RBAC1_' + info.project.name.toUpperCase() + '_REFRESH'
  179 |   const cookie = (await context.cookies()).find(item => item.name === cookieName)!.value
  180 |   const before = refreshCount(page)
  181 |   const network = await isolateAuthWorkerNetwork(browser)
  182 |   let businessAttempts = 0
  183 |   await page.route('**/api/auth/me/permission-check?*', route => {
  184 |     businessAttempts++
  185 |     return route.fulfill({ status: 401, contentType: 'application/json',
  186 |       body: '{"code":"ACCESS_TOKEN_EXPIRED","message":"过期路径测试桩"}' })
  187 |   })
  188 |   try {
  189 |     await network.setOffline(true)
  190 |     await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  191 |     await expect(access(page).getByRole('alert')).toContainText('无法连接后端')
  192 |     await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  193 |     expect((await context.cookies()).find(item => item.name === cookieName)!.value === cookie).toBe(true)
  194 |     await network.setOffline(false)
  195 |     await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  196 |     await expect(access(page).getByRole('alert')).toContainText('无法连接后端')
  197 |     expect(businessAttempts).toBe(2)
  198 |     expect(refreshCount(page)).toBe(before)
  199 |     await page.getByRole('button', { name: '退出登录' }).click()
  200 |     await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  201 |     await page.getByLabel('用户名').fill('engineering-requester')
  202 |     await page.getByLabel('密码').fill('1234')
  203 |     await page.getByRole('button', { name: '登录', exact: true }).click()
> 204 |     await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
      |                                                                            ^ Error: expect(locator).toBeVisible() failed
  205 |   } finally { await network.setOffline(false); await network.close() }
  206 | })
  207 | 
```