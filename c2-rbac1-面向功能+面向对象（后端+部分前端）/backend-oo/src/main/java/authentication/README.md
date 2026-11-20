# 登录、会话与工作区

## 核心功能

本模块把账号密码转换为可信员工身份，管理登录和退出，并计算该员工可进入的管理台及业务系统。系统准入和具体动作授权是两个判断：能进入系统不代表能查看或审批申请。两版本模块实现相同，隔离由各自 Cookie、Redis 命名空间和 MySQL 库实现。

## 文件分工

| 文件 | 主要职责 |
| --- | --- |
| [AuthController](AuthController.java) | 登录、CSRF、当前会话、系统列表及退出的 HTTP 适配 |
| [SessionAuthentication](SessionAuthentication.java) | 凭据核验、轮换会话 ID、保存 SecurityContext、结束会话 |
| [WorkspaceService](WorkspaceService.java) | 活跃员工、组织上下文、系统准入和默认工作区 |
| [LoginAccount](LoginAccount.java) / [LoginAccountRepository](LoginAccountRepository.java) | 员工与账号密码哈希的 JPA 映射及查询 |
| [PasswordConfiguration](PasswordConfiguration.java) | 提供 PBKDF2 `PasswordEncoder` |
| [DemoLoginAccounts](DemoLoginAccounts.java) | 显式演示开关及命令下初始化八个合成账号 |
| [SessionStoreFailure](SessionStoreFailure.java) | 沿异常原因链识别 Spring Redis 与 Lettuce 会话故障 |
| [LoginDTO](dto/LoginDTO.java)、[CurrentSessionVO](vo/CurrentSessionVO.java) 等 | 请求校验及公开身份、系统、CSRF 响应；不暴露账号实体 |

## 核心函数的实现

### 登录：`AuthController.login` 与 `SessionAuthentication.verify/start`

1. Controller 用 `@Valid` 校验登录 DTO。身份和密码不能通过 URL 中的角色参数替代。
2. `verify` 按用户名找员工，再读取员工关联的 `LoginAccount`。即使没有账号，也使用构造时生成的备用密码哈希执行一次 `matches`，减少未知账号直接早退造成的密码计算成本差异。
3. 密码错误、缺账号、员工停用或组织未匹配统一拒绝，不在登录响应中区分具体账号状态。
4. Controller 先调用 `workspaces.current(employee)` 并形成响应，再调用 `sessions.start` 建立登录上下文，避免工作区计算失败后仍留下成功登录。
5. `start` 创建或取得会话、`changeSessionId` 抵御会话固定，清除旧 CSRF，再将仅含可信员工 ID 的认证上下文保存到 `HttpSessionSecurityContextRepository` 和当前线程上下文。
6. 登录后客户端重新领取 CSRF。会话不保存密码、角色闭包或永久有效权限。

### 工作区：`WorkspaceService.context/current/listSystems`

`findActiveEmployee` 每次从 MySQL 读取员工，`canSignIn` 要求启用且有明确组织。`context` 将员工、组织及管理员配置转换为不可变上下文：GLOBAL 可进入所有系统；普通员工覆盖所属组织及父公司；局部管理员依据管理组织计算范围，公司管理员另含直属部门。当前模型支持公司及直接下属部门，不是任意深度组织树。

`current` 给管理员优先选择管理工作区，再加入可见业务系统；普通员工依次尝试本部门默认系统、父公司默认系统，无可用项时允许默认值为空。业务路由本身为 `/business/{code}/`，`/func/`、`/oo/` 由前端版本导航和代理保留。

`listSystems` 读取可进入系统并补组织名称。此列表不替代 `AuthorizationService` 的逐动作实时判权。

### 退出和演示初始化

`end` 通过 `SecurityContextLogoutHandler` 清理安全上下文并使本版会话失效。另一版的会话由不同 Cookie 和 Redis 命名空间保存，因此不会一并退出。

`DemoLoginAccounts.run` 同时要求 `rbac.demo.accounts-enabled=true` 和 `--init-demo`，并在员工初始化之后运行。当前演示密码为代码中的 `1234` 常量，只用于合成账号；真实哈希由 PBKDF2 产生。已有同密码账号不重复编码，密码不一致时重置演示账号。这里不能写成演示密码已由环境变量注入。

## 调用其他模块与被调用

- `AuthController → SessionAuthentication → EmployeeRepository / LoginAccountRepository / PasswordEncoder`。
- `AuthController → WorkspaceService → Employee / Organization / AdministratorScope / BusinessSystem Repository`。
- [SecurityConfiguration](../authorization/SecurityConfiguration.java) 每次请求复验活跃员工；[AuthorizationService](../authorization/AuthorizationService.java) 复用工作区上下文检查系统准入。
- [AssignmentService](../assignment/AssignmentService.java) 用 `canSignIn` 阻止向不可登录员工新增角色。
- [LayerLoggingAspect](../contract/LayerLoggingAspect.java) 显式跟踪 `verify/start/end`，不在认证类中记录凭据。

## 使用的技术及原因

Spring MVC 与 Bean Validation 处理 JSON 和输入边界；Spring Security 的 `@AuthenticationPrincipal` 与 SecurityContext 提供可信身份；PBKDF2 加盐哈希避免明文密码存储；Spring Session Redis 支持服务端会话失效；只读事务、不可变 record/集合使工作区上下文可明确传递。`CurrentSessionVO`、`BusinessSystemVO` 当前使用显式字段映射，不把所有 VO 都描述为 CloneFactory 生成。

## API、失败与验证

`GET /api/auth/csrf`、`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`、`GET /api/business-systems` 的精确响应见 [共同 OpenAPI](../../../../../api/openapi.yaml)。响应采用 `no-store`。

凭据失败为 `401 INVALID_CREDENTIALS`；失效身份为 `401 AUTHENTICATION_REQUIRED`；非 JSON 登录为 415。Redis 故障由外层过滤器或统一异常处理转换为 `503 SESSION_UNAVAILABLE`，不是由每个 Controller 重复捕获。现有验证入口为 `测试/后端/common/java/contract/AuthSessionTest.java`；结果与环境见 [历史测试记录](../../../../../测试/历史/双版本测试记录.md)，测试重组已完成，后续按 [收尾清单](../../../../../收尾清单.md) 执行。
