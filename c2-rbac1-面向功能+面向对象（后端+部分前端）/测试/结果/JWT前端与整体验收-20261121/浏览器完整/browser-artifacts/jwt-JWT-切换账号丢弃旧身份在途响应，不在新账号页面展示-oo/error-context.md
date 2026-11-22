# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: jwt.spec.ts >> JWT 切换账号丢弃旧身份在途响应，不在新账号页面展示
- Location: tests\jwt.spec.ts:83:1

# Error details

```
Test timeout of 30000ms exceeded.
```

```
Error: page.waitForResponse: Test timeout of 30000ms exceeded.
```

# Page snapshot

```yaml
- generic [ref=f2e2]:
  - banner [ref=f2e3]:
    - generic [ref=f2e4]: OOAD RBAC
    - generic [ref=f2e9]:
      - link "切换版本" [ref=f2e10] [cursor=pointer]:
        - /url: /
        - text: 面向对象版
      - generic [ref=f2e11]:
        - generic [ref=f2e12]: 当前系统
        - combobox "当前系统" [ref=f2e13]:
          - option "示例公司申请系统"
          - option "示例财务申请系统" [selected]
      - generic [ref=f2e14]: finance-employee
      - button "部门与员工" [ref=f2e15] [cursor=pointer]
      - button "退出登录" [ref=f2e16] [cursor=pointer]
  - main [ref=f2e17]:
    - generic [ref=f2e18]:
      - paragraph [ref=f2e19]: 示例财务申请系统
      - heading "业务申请" [level=1] [ref=f2e20]
    - region "我的角色与权限" [ref=f2e21]:
      - group [ref=f2e22]:
        - generic "我的角色与权限" [ref=f2e23] [cursor=pointer]
        - option "查看申请列表"
        - option "查看申请详情"
        - option "发起申请"
        - option "修改申请"
        - option "撤回申请"
        - option "审批申请" [selected]
        - option "驳回申请"
        - option "删除已驳回申请"
    - generic [ref=f2e24]:
      - generic [ref=f2e25]:
        - generic [ref=f2e26]:
          - heading "申请列表" [level=2] [ref=f2e27]
          - generic [ref=f2e28]:
            - button "刷新" [ref=f2e29] [cursor=pointer]
            - button "发起申请" [ref=f2e30] [cursor=pointer]
        - table [ref=f2e32]:
          - rowgroup [ref=f2e33]:
            - row [ref=f2e34]:
              - columnheader "申请" [ref=f2e35]
              - columnheader "状态" [ref=f2e36]
              - columnheader "操作" [ref=f2e37]
          - rowgroup [ref=f2e38]:
            - row [ref=f2e39]:
              - cell "#13 示例申请 1" [ref=f2e40]
              - cell "待审批" [ref=f2e41]
              - cell [ref=f2e42]:
                - generic [ref=f2e43]:
                  - button "查看详情" [ref=f2e44] [cursor=pointer]
                  - button "审批" [ref=f2e45] [cursor=pointer]
            - row [ref=f2e46]:
              - cell "#14 示例申请 2" [ref=f2e47]
              - cell "待审批" [ref=f2e48]
              - cell [ref=f2e49]:
                - generic [ref=f2e50]:
                  - button "查看详情" [ref=f2e51] [cursor=pointer]
                  - button "审批" [ref=f2e52] [cursor=pointer]
            - row [ref=f2e53]:
              - cell "#15 示例申请 3" [ref=f2e54]
              - cell "待审批" [ref=f2e55]
              - cell [ref=f2e56]:
                - generic [ref=f2e57]:
                  - button "查看详情" [ref=f2e58] [cursor=pointer]
                  - button "审批" [ref=f2e59] [cursor=pointer]
            - row [ref=f2e60]:
              - cell "#16 示例申请 4" [ref=f2e61]
              - cell "待审批" [ref=f2e62]
              - cell [ref=f2e63]:
                - generic [ref=f2e64]:
                  - button "查看详情" [ref=f2e65] [cursor=pointer]
                  - button "审批" [ref=f2e66] [cursor=pointer]
            - row [ref=f2e67]:
              - cell "#17 示例申请 5" [ref=f2e68]
              - cell "待审批" [ref=f2e69]
              - cell [ref=f2e70]:
                - generic [ref=f2e71]:
                  - button "查看详情" [ref=f2e72] [cursor=pointer]
                  - button "审批" [ref=f2e73] [cursor=pointer]
            - row [ref=f2e74]:
              - cell "#18 示例申请 6" [ref=f2e75]
              - cell "待审批" [ref=f2e76]
              - cell [ref=f2e77]:
                - generic [ref=f2e78]:
                  - button "查看详情" [ref=f2e79] [cursor=pointer]
                  - button "审批" [ref=f2e80] [cursor=pointer]
            - row [ref=f2e81]:
              - cell "#27 财务验收 1795275765394" [ref=f2e82]
              - cell "已审批" [ref=f2e83]
              - cell [ref=f2e84]:
                - button "查看详情" [ref=f2e86] [cursor=pointer]
      - complementary [ref=f2e87]:
        - region "操作结果" [ref=f2e88]:
          - heading "操作结果" [level=2] [ref=f2e89]
          - paragraph [ref=f2e90]: 完成操作后，在这里查看结果。
```

# Test source

```ts
  9   | 
  10  | async function login(page: Page, username = 'engineering-requester') {
  11  |   trackAuth(page)
  12  |   await page.goto(prefix() + '/')
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
  98  |   try {
  99  |     await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  100 |     await requestSeen
  101 |     await peer.getByRole('button', { name: '退出登录' }).click()
  102 |     await expect(page.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  103 |     // 等待退出自身的整页跳转完成，再在现有登录页操作，避免测试制造相互打断的导航。
  104 |     await expect(peer.getByRole('heading', { name: '登录工作台' })).toBeVisible()
  105 |     await peer.getByLabel('用户名').fill('finance-employee')
  106 |     await peer.getByLabel('密码').fill('1234')
  107 |     await peer.getByRole('button', { name: '登录', exact: true }).click()
  108 |     await expect(page.getByText('finance-employee', { exact: true })).toBeVisible()
> 109 |     const oldResponse = page.waitForResponse(response => response.url().includes('/api/auth/me/permission-check?'))
      |                              ^ Error: page.waitForResponse: Test timeout of 30000ms exceeded.
  110 |     release()
  111 |     await oldResponse
  112 |     await expect(page.getByText('engineering-inherited-approver', { exact: true })).toHaveCount(0)
  113 |     await expect(page.locator('.subtitle')).toHaveText('示例财务申请系统')
  114 |   } finally { release() }
  115 | })
  116 | 
  117 | test('JWT 网络结果未知的写入不自动重放', async ({ page }) => {
  118 |   await login(page)
  119 |   const title = '未知结果不重放 ' + Date.now()
  120 |   let submissions = 0
  121 |   await page.route('**/api/business-systems/demo-engineering-app/requests', async route => {
  122 |     if (route.request().method() !== 'POST') { await route.continue(); return }
  123 |     submissions++
  124 |     await route.fetch() // 真实后端已提交，再丢弃响应模拟未知执行结果。
  125 |     await route.abort('failed')
  126 |   })
  127 |   await page.getByRole('button', { name: '发起申请', exact: true }).click()
  128 |   await page.getByRole('dialog').getByLabel('申请标题').fill(title)
  129 |   await page.getByRole('dialog').getByRole('button', { name: '保存申请' }).click()
  130 |   await expect(page.getByRole('alert')).toContainText('无法连接后端')
  131 |   expect(submissions).toBe(1)
  132 |   const result = await (await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests?pageSize=100')).json()
  133 |   expect(result.items.filter((item: { title: string }) => item.title === title)).toHaveLength(1)
  134 | })
  135 | 
  136 | test('JWT 只有确定的过期允许写入重试一次，后端只创建一条', async ({ page }) => {
  137 |   await login(page)
  138 |   const old = observedBearer(page)
  139 |   const before = refreshCount(page)
  140 |   const title = '过期后单次重试 ' + Date.now()
  141 |   let attempts = 0
  142 |   await page.route('**/api/business-systems/demo-engineering-app/requests', async route => {
  143 |     if (route.request().method() !== 'POST') { await route.continue(); return }
  144 |     attempts++
  145 |     if (route.request().headers()['authorization'] === 'Bearer ' + old) {
  146 |       await route.fulfill({ status: 401, contentType: 'application/json', body: '{"code":"ACCESS_TOKEN_EXPIRED","message":"过期路径测试桩"}' })
  147 |     } else await route.continue()
  148 |   })
  149 |   await page.getByRole('button', { name: '发起申请', exact: true }).click()
  150 |   await page.getByRole('dialog').getByLabel('申请标题').fill(title)
  151 |   await page.getByRole('dialog').getByRole('button', { name: '保存申请' }).click()
  152 |   await expect(page.getByRole('region', { name: '申请详情' })).toContainText(title)
  153 |   expect(attempts).toBe(2)
  154 |   expect(refreshCount(page) - before).toBe(1)
  155 |   const result = await (await apiGet(page, prefix() + '/api/business-systems/demo-engineering-app/requests?pageSize=100')).json()
  156 |   expect(result.items.filter((item: { title: string }) => item.title === title)).toHaveLength(1)
  157 | })
  158 | 
  159 | test('JWT 普通403不刷新凭证，也不清除已登录身份', async ({ page }) => {
  160 |   await login(page)
  161 |   await access(page).getByText('我的角色与权限', { exact: true }).click()
  162 |   const before = refreshCount(page)
  163 |   await page.route('**/api/auth/me/permission-check?*', route => route.fulfill({ status: 403,
  164 |     contentType: 'application/json', body: '{"code":"PERMISSION_DENIED","message":"拒绝路径测试桩"}' }))
  165 |   await access(page).getByRole('button', { name: '检查前端权限', exact: true }).click()
  166 |   await expect(access(page).getByRole('alert')).toContainText('拒绝路径测试桩')
  167 |   expect(refreshCount(page)).toBe(before)
  168 |   await expect(page.getByText('engineering-requester', { exact: true })).toBeVisible()
  169 | })
  170 | 
```