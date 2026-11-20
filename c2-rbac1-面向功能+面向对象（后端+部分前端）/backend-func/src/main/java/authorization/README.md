# 请求安全与统一实时授权

## 责任边界

本模块分成请求安全和业务判权两部分。[SecurityConfiguration](SecurityConfiguration.java) 负责会话身份、CSRF、请求路由和故障响应；[AuthorizationService](AuthorizationService.java) 负责管理员资格及指定系统业务动作的实时权限。页面隐藏按钮不替代本模块的服务端检查。

[AdministratorScope](AdministratorScope.java) 及仓储记录管理员类型和组织范围。管理动作来自管理员配置，普通业务角色不能通过获得 `request:approve` 变成管理员。

## 核心函数和判权顺序

### `assignmentScope`

只识别代码中五类管理动作。组织策略管理仅允许 GLOBAL，其他受支持管理动作返回现有管理员范围。该函数不完成具体资源的组织检查；后续 management、assignment 或继承用例继续检查目标资源和受影响范围。

### `requireBusinessPermission` 与 `permissionContext/isAllowed`

1. `systemContext` 通过 `WorkspaceService.findActiveEmployee` 复验员工；失效身份 401。再使用 `Context.canEnter` 校验系统准入，越界为 `SYSTEM_ACCESS_DENIED`。
2. 按 `systemId + code` 查动作定义，目标系统没有该动作时拒绝，避免同码跨系统借权。
3. `permissionContext` 收集系统归属及其上级组织；普通身份还加入员工组织及上级。读取直接角色作为 roots，再展开本次继承快照；roots 为空时不扫描继承图。
4. `isAllowed` 先查适用组织策略，存在任一 DISABLED 就拒绝，早于全局管理员特权。
5. 未停用时 GLOBAL 通过；普通员工没有可达来源则拒绝。
6. 无 REOPENED 时任一可达角色直接拥有权限即可；存在 REOPENED 时用 `RolePermissionRepository.hasRegrantedSource` 要求某一个直接来源覆盖全部重开组织。子查询按来源统计，防止把不同来源的重授拼接。

系统准入统一由私有 `systemContext` 随业务授权执行；原独立 `requireSystemAccess` 因没有调用已移除。申请列表和详情继续调用 `requireBusinessPermission(..., request:read)`，不能仅凭系统准入读取申请。

### `effectivePermissions`

先构造同一授权上下文，再按系统权限 ID 升序逐动作调用 `isAllowed`。结果按权限 ID 唯一，直接和多条继承路径不会重复展示。员工有效权限查询在 organization 先校验查询者范围，再调用本函数计算目标员工结果。

## 实时性的准确含义

Redis 只保存身份会话，权限来自当前版本 MySQL 的分配、继承和策略记录，不做跨请求权限缓存。授权读取不获取图写锁，也不承诺整个多次查询处于串行化快照。撤权事务提交后，员工同一会话的下一次请求按新关系判断；当前实现不取消已经通过鉴权的在途事务。

功能版的闭包通过 `RoleInheritanceGraph/RoleInheritanceRules`，对象版通过 `RoleHierarchy`；其余组织授权规则相同，不能声称对象版此模块已全面领域化。

## 请求过滤与会话故障边界

- 安全配置只在 Servlet Web 模式启用，初始化命令不建立 Web 安全链。
- 使用 `HttpSessionSecurityContextRepository` 和 `HttpSessionCsrfTokenRepository`，Spring Session 将会话持久化到 Redis。禁用默认 Basic、formLogin、默认 logout 和 request cache，沿用项目认证端点。
- 登录、领取 CSRF 的访问规则与业务资源分开；`permitAll` 不等于关闭 CSRF。已声明路由采用 authenticated 或管理动作检查，未知请求 `denyAll`。
- `activeEmployeeFilter` 在 `AuthorizationFilter` 前复验 Long 员工 principal，停用或待匹配时结束旧会话并返回 401；数据库故障为 `503 AUTHORIZATION_UNAVAILABLE`。
- `redisSessionErrors` 在 `SessionRepositoryFilter` 之外捕获会话存储异常链，尚未提交响应时返回 `503 SESSION_UNAVAILABLE`；已提交的响应不能重新写入。
- 过滤器边界直接输出统一 `ApiErrorVO` 和 `no-store`，不假设所有安全异常都进入 MVC Advice。

## 调用关系、技术与验证

`HTTP → Session/CSRF/活跃员工/路由检查 → Controller → Service → AuthorizationService`。申请用例或对象版 `RequestContextAdapter` 调统一授权；管理与分配用例使用 `assignmentScope`；本模块依赖 WorkspaceService、管理员、组织、权限、直接分配、策略和继承存储。

技术包括 Spring Security FilterChain、Servlet 过滤器顺序、SecurityContext、服务端 CSRF、JPA 当前关系查询、相关子查询及集合闭包。错误按 401/403 与基础设施 503 区分，鉴权无法完成时拒绝执行业务。

现有 `AuthSessionTest` 覆盖登录、范围、停用、同源重授、原会话撤权和依赖故障；执行条件与历史结果见 [测试记录](../../../../../测试/历史/双版本测试记录.md)。本次未改变路由、Cookie 或授权语义，C3 角色约束及激活仍为后续阶段。
