# C2 前端运行与验收

`/func/` 连接面向功能后端，`/oo/` 连接面向对象后端。两个入口使用同一个 Vue 构建，由同源代理按路径选择后端和会话。

1. 在两套后端的独立演示库各运行一次 `--init-demo`。启用 `RBAC_DEMO_ACCOUNTS_ENABLED=true` 后，命令会创建或更新以下八个演示账号，密码统一为 `1234`。
2. 对需要全量验收的库分别运行 `--import-excel=<原表目录>`。两种初始化命令执行后退出。
3. 启动功能版后端使用 `SERVER_PORT=18181`，对象版使用 `SERVER_PORT=18182`，再在本目录运行 `npm ci` 和 `npm run dev`。若使用其他端口，可配置 `RBAC_FUNC_API_TARGET` 和 `RBAC_OO_API_TARGET`。打开 [本机首页](http://127.0.0.1:5173/) 选择版本。

| 账号 | 用途 |
| --- | --- |
| `super-admin` | 全局管理 |
| `company-a-admin` | 公司管理与公司申请 |
| `engineering-employee` | 工程部申请 |
| `engineering-admin` | 部门管理与继承验收 |
| `finance-employee` | 财务部申请与部门隔离 |
| `engineering-requester` | 发起、修改、撤回本人的工程申请，删除本人已驳回申请 |
| `engineering-inherited-approver` | 通过「示例继承审批主管 → 示例继承审批来源」获得工程审批。移除该边可验证同会话实时撤权 |
| `company-b-employee` | 查看乙公司系统的申请，验证跨公司和无动作权限场景 |

对已经初始化的演示库运行同一命令可补齐新增账号。演示初始化保留用户对继承关系和组织策略的修改。如果工程审批已重新开放，需由管理员对「示例继承审批来源」重授后再用继承账号审批。

读取申请需要该系统的 `request:read`。八个演示账号按所属工作区配置了查看角色，全局管理员按既有规则授权。其他账号升级后由管理员显式授予查看权。撤销查看权后，原页面下一次读取会清空旧列表和详情。处理已有申请还需要相应动作权限。

用 `super-admin` 登录后，打开「角色管理 → 测试工程主管 → 管理权限」。这个角色从「示例继承审批来源」继承「工程申请审批」，权限页会标出继承权限的只读状态。直接授权时先选择业务系统，再选择动作。角色配置与员工在组织策略下的实际权限可分别在「管理权限」和「员工管理 → 有效权限」核对。

在独立演示库上运行生产构建验收。浏览器用例会提交申请、修改角色和组织策略。

```powershell
npm run build
$env:RBAC_BROWSER_PREVIEW = 'true'
$env:RBAC_BROWSER_CHANNEL = 'msedge'
$env:RBAC_EXCEL_REVIEW_DIR = '<已导入的三份课程工作簿目录>'
npm run test:e2e -- --timeout=60000
```

设置 `RBAC_EXCEL_REVIEW_DIR` 时，用 Python 3 核对两版的三份 Excel 全量分页内容。测试报告保留摘要，不包含原表行内容。业务日志归口 `rbac1.func.request` 和 `rbac1.oo.request`。参见 [C2 后端运行说明](../README.md)。

当前命令使用现有 `frontend/tests/`。后续按 [收尾清单](../收尾清单.md) 在 C2 的 `测试/` 重新制作并集中交付测试代码、说明和执行结果，调整后同步 Playwright 配置及本页命令。本次未迁移文件或重跑验收。
