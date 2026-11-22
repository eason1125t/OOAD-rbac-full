# 请求安全与统一实时授权

## 责任边界

本模块分成请求安全和业务判权两部分。[SecurityConfiguration](SecurityConfiguration.java) 负责Bearer身份、Cookie入口CSRF、请求路由和故障响应；[AuthorizationService](AuthorizationService.java) 负责管理员资格及指定系统业务动作的实时权限。页面隐藏按钮不替代本模块的服务端检查。

[AdministratorScope](AdministratorScope.java) 及仓储记录管理员类型和组织范围。管理动作来自管理员配置，普通业务角色不能通过获得 `request:approve` 变成管理员。

## 核心函数和判权顺序

### `assignmentScope`

只识别代码中五类管理动作。组织策略管理仅允许 GLOBAL，其他受支持管理动作返回现有管理员范围。该函数不完成具体资源的组织检查；后续 management、assignment 或继承用例继续检查目标资源和受影响范围。

### `requireBusinessPermission` 与 `permissionContext/isAllowed`

1. `systemContext` 通过 `CurrentEmployeeService.requireActive` 复验员工；失效身份 401。再使用 `Context.canEnter` 校验系统准入，越界为 `SYSTEM_ACCESS_DENIED`。
2. 按 `systemId + code` 查动作定义，目标系统没有该动作时拒绝，避免同码跨系统借权。
3. `permissionContext` 收集系统归属及其上级组织；普通身份还加入员工组织及上级。读取直接角色作为 roots，再展开本次继承快照；roots 为空时不扫描继承图。
4. `isAllowed` 先查适用组织策略，存在任一 DISABLED 就拒绝，早于全局管理员特权。
5. 未停用时 GLOBAL 通过；普通员工没有可达来源则拒绝。
6. 无 REOPENED 时任一可达角色直接拥有权限即可；存在 REOPENED 时用 `RolePermissionRepository.hasRegrantedSource` 要求某一个直接来源覆盖全部重开组织。子查询按来源统计，防止把不同来源的重授拼接。

系统准入统一由私有 `systemContext` 随业务授权执行；原独立 `requireSystemAccess` 因没有调用已移除。申请列表和详情继续调用 `requireBusinessPermission(..., request:read)`，不能仅凭系统准入读取申请。

### `effectivePermissions`

先构造同一授权上下文，再按系统权限 ID 升序逐动作调用 `isAllowed`。结果按权限 ID 唯一，直接和多条继承路径不会重复展示。员工有效权限查询在 organization 先校验查询者范围，再调用本函数计算目标员工结果。

## 实时性的准确含义

Redis保存独立登录状态和刷新摘要，权限来自当前版本 MySQL 的分配、继承和策略记录，不做跨请求权限缓存。授权读取不获取图写锁，也不承诺整个多次查询处于串行化快照。撤权事务提交后，员工同一次登录的下一次请求按新关系判断；当前实现不取消已经通过鉴权的在途事务。

功能版的闭包通过 `RoleInheritanceGraph/RoleInheritanceRules`，对象版通过 `RoleHierarchy`；其余组织授权规则相同，不能声称对象版此模块已全面领域化。

## JWT过滤与依赖故障边界

- 只在Servlet Web模式装配安全链；使用STATELESS和NullSecurityContextRepository，不读写认证HttpSession，Spring Session Redis依赖已移除。
- Bearer由Spring Resource Server处理，JwtAccessTokens验证签名/字段，JwtEmployeeAuthentication先查Redis登录归属/期限，再查最新员工；观察到失效员工时结束当前sid。
- 登录、刷新、退出、取CSRF使用独立Cookie入口；前三者要求Cookie及X-CSRF-TOKEN。入口忽略Authorization，过期访问JWT不阻止刷新/退出。业务路由仅接受Bearer，不能靠刷新Cookie或旧Session获得身份。
- Controller在登录成功和退出时轮换CSRF；NullAuthenticatedSessionStrategy防止逐请求Bearer认证清理CSRF。JSON中的掩码token继续由XOR处理器校验，不能以请求参数代替必需请求头。
- 已声明路由采用authenticated或原管理动作检查；未知请求denyAll，实际业务仍执行权限、对象范围和状态检查。
- dependencyErrors在Bearer认证之前包住链路；认证Redis故障/记录损坏返回503 AUTHENTICATION_UNAVAILABLE，授权数据库故障返回503 AUTHORIZATION_UNAVAILABLE。只验签不放行，故障不当作allowed=false，已提交响应不重写。
- 401保留Bearer challenge；仅验证器确认的单纯过期为ACCESS_TOKEN_EXPIRED，其余无效/已撤销身份为AUTHENTICATION_REQUIRED。过滤器直接写ApiErrorVO和no-store，不依赖MVC Advice拦截全部安全异常。

## 调用关系、技术与验证

`HTTP → Bearer验签/Redis登录/活跃员工/路由检查 → Controller → Service → AuthorizationService`。申请用例或对象版 `RequestContextAdapter` 调统一授权；管理与分配用例使用 `assignmentScope`；本模块依赖 CurrentEmployeeService、WorkspaceService、管理员、组织、权限、直接分配、策略和继承存储。

技术包括 Spring Security FilterChain、Servlet 过滤器顺序、SecurityContext、服务端 CSRF、JPA 当前关系查询、相关子查询及集合闭包。错误按 401/403 与基础设施 503 区分，鉴权无法完成时拒绝执行业务。

现有 `AuthSessionTest` 覆盖登录、范围、停用、同源重授、原会话撤权和依赖故障；执行条件与历史结果见 [测试记录](../../../../../测试/README.md)。本次未改变路由、Cookie 或授权语义，C3 角色约束及激活仍为后续阶段。

## 第三步新增的授权查询复用

`hasBusinessPermission(Employee, system, code)` 接收上层已读取的可信员工，使用同一系统准入、permissionContext 和 isAllowed，已知无权返回 false。未知权限在此查询入口抛 PERMISSION_NOT_FOUND；旧 requireBusinessPermission 的未知动作仍保持 PERMISSION_DENIED，不改变既有实际操作契约。

`describe` 使用本次角色图快照分别计算各直接根闭包，汇总可达角色；从“其他直接根能否到达”得出继承来源。目标系统的配置权限来源一次批量查询，再复用图的 dependingRoles 得出相关角色。有效权限仍由同一 isAllowed 逐项计算；来源和权限不跨请求缓存。一次读事务不等于对并发写入加锁或取消已经鉴权的在途操作。

角色查询结果由 CurrentAuthorizationService 补名称和做 VO 白名单映射，不能把请求参数 ID 当作当前员工。公共 SystemAuthorization 是内部服务结果，不直接作为 HTTP 响应输出。测试及变更边界见 [第三步验证](../../../../../测试/README.md)。

## 第四步目录读取路由

只有GET /api/employees和GET /api/departments从管理资格路由调整为authenticated，再由目录Service按可信身份应用读取范围和模式。默认原表目录仍要求管理员；tree和最小员工列表可供普通员工读取本组织。详情、角色/权限、公司列表及写入路由的管理资格不变；不要将普通列表开放理解成管理权限开放。业务系统准入及角色/组织策略判权未改。

## 第5步：API映射与既有授权复用

ApiOperationRegistry用JdbcTemplate访问V9两表，复合主键区分系统及operationId；空集合、跨系统引用和RequestOperation定义漂移均失败关闭。effectivePermissions新增可信Employee重载，沿用同一isAllowed、角色继承、组织停用与同源重授，身份适配和规则计算仍分开。

初始化仅为四个演示系统补不存在的操作，已有损坏数据导致回滚。ManagementMaintenanceService删除权限前也检查API映射引用。映射查询不进入目标业务授权流程；实际申请依赖规范操作定义及最新授权。[测试与迁移](../../../../../测试/README.md)。

JWT第3部分194/198项、真实Redis并发与使用限制见[生命周期验证](../../../../../测试/README.md)；前端和部署接入待第4部分，图件待最终交付更新。
