# C1 双端页面

Vue 和 TypeScript 工程提供共用登录页、权限管理工作区 `/admin/` 和业务模拟工作区 `/business/{systemCode}/`。两个工作区用独立页面承载，同源共享 Cookie。默认跳转和可切换的系统以当前身份接口为准。

## 运行

1. 按 [部署运行说明](../部署运行说明/README.md) 启动 C1 后端、MySQL 和 Redis，再初始化演示账号。
2. 安装 Node.js 22.12+ 或 24，从当前 `frontend` 目录执行下列命令。

```sh
npm ci
npm run dev
```

打开 `http://127.0.0.1:5173`。Vite 将同源 `/api` 请求转发到 `http://127.0.0.1:8080`。如果后端端口不同，PowerShell 中先设 `$env:RBAC_API_TARGET='http://127.0.0.1:38082'`。Bash 中用 `export RBAC_API_TARGET=http://127.0.0.1:38082`。本机 HTTP 演示使用默认的非 Secure Cookie 策略。

## 演示

用普通窗口登录 `super-admin`，用无痕窗口登录 `engineering-employee`。密码使用初始化时配置的演示密码。两个普通标签页会共享登录 Cookie。

1. 在「员工管理」找到「示例工程员工」，点击「管理角色」。新初始化的环境已有「示例工程审批员」分配，先撤销它，保留该角色的「工程申请审批」权限。
2. 管理端选择「示例工程审批员」并点击「授予角色」。员工在默认的工程申请系统审批任一待审批申请，页面应显示「鉴权通过／执行成功」。
3. 管理端点击「撤销角色」。员工保持登录，在原页面审批另一条待审批申请，应显示「鉴权拒绝／未执行」，记录仍为待审批。
4. 如需演示角色–权限关系，在「角色管理」找到「示例工程审批员」，点击「管理权限」，再授予或撤销「工程申请审批」。
5. 超级管理员可在「权限管理」的「生效权限配置」中打开「组织策略」，选择部门或公司，执行停用、重新开放及重授。停用会清除受影响的重授，普通员工在重新开放后仍需显式重授。

## 验证

`npm run build` 检查 TypeScript 并生成 `dist/`。构建保留已有文件，发布时以新生成的 `index.html` 引用的资源为准。若用静态服务器发布，将页面回退到 `index.html`，并在同源反代 `/api` 到后端。 [Vite 构建指南](https://vite.dev/guide/build.html)

端到端测试使用已初始化的隔离演示库，至少需要两条待审批的工程申请。测试会修改演示分配并审批一条申请。

```powershell
npx playwright install chromium
$env:RBAC_DEMO_PASSWORD = Read-Host '隔离环境的演示密码' -MaskInput
npm run test:e2e
```

也可设置 `RBAC_BROWSER_CHANNEL=msedge` 复用已安装的 Edge。如果本机已有 5173 端口运行的前端，可用 `RBAC_FRONTEND_TEST_PORT=5174` 单独运行测试服务。测试使用两个独立浏览器上下文，检查撤权后员工的 `SESSION` 不变。 [Playwright 会话隔离说明](https://playwright.dev/docs/browser-contexts)

## 管理平台复用

- `AdminConsole.vue` 管理侧栏及权限页签。权限管理提供「生效权限配置」和「Excel 原表清单」。
- `adminSections.ts` 定义目录、字段和 API，`AdminDirectory.vue` 渲染范围分页及维护表单，支持按名称选择组织和业务系统。
- `AssignmentEditor.vue` 从员工或角色行维护直接分配。 `PermissionPolicy.vue` 从业务权限行管理组织策略。
- C2 沿用导航和目录，在角色管理内接入继承与有效权限。C3 沿用同一外壳，在授权策略中接入约束。两阶段的新增页面随对应 API 实现。

真实 Excel 的本机页面对照及生产构建的双端浏览器回归均已通过，参见 [本地验收记录](../docs/本地验收记录.md)。
