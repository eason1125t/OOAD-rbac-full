> 历史归档：保留整理前的阶段记录；其中“当前”“待完成”和测试数量均属于原批次。现行操作及状态以[统一说明](../项目总结.md)为准。

# C2 JWT 认证迁移方案与接口契约

日期：2026-11-21（Asia/Shanghai）。第1～3部分已完成后端；第4部分已接入前端及部署并移除退役Session类，第5部分于2026-11-22完成[整体验收](../../测试/归档/JWT前端与整体验收.md)。本机运行进程后续已成套切换，见[切换记录](../../测试/归档/JWT本机切换记录.md)，第6部分图件、交付与Apifox已完成，见[最终交付](../../测试/归档/JWT迁移最终交付.md)。正式接口以[主OpenAPI](../../api/openapi.yaml)和[统一身份八项契约](../../api/统一身份鉴权接口契约.md)为准，不把历史Session测试当作JWT证据。

## 1. 范围与已确认决策

本轮在现有功能版、对象版中局部迁移认证，共用一个前端和一致的外部行为。保留两版设计差异、RBAC1 继承、组织权限停用/重开/同源重授、工作区、八项身份能力和实际业务授权。课程集群由队友部署；不在本轮开放跨项目 CORS、不搭建独立 OAuth/OIDC 认证中心、不实现跨项目单点登录。

| 决策 | 已确认内容 |
| --- | --- |
| 认证方式 | Spring Security 验证 Bearer JWT，业务请求不再从 HttpSession 恢复身份 |
| 访问凭证 | 短期签名 JWT；只承载身份及凭证校验字段，不承载角色、有效权限或组织策略快照 |
| 有效期（Q1） | 访问 JWT 最长 15 分钟；单次登录绝对上限 8 小时；刷新不得延长登录上限；取消原 Session 的 30 分钟闲置超时语义 |
| 多处登录（Q2） | 同一账号可在多个浏览器独立登录；退出只撤销当前登录，不影响其他浏览器或另一版后端 |
| 刷新轮换（Q3） | 每次刷新原子轮换；确认已使用的旧刷新凭证被重用时，撤销对应整次登录；不设重用宽限期 |
| 用户追加约束 | 禁止使用 tokenVersion、用户版本号或递增版本比较来使 JWT 失效；不新增这种数据库字段或 Redis 计数器 |
| 撤销方式 | 每次独立登录生成随机 sid；Redis 保存该次登录是否有效；退出、刷新重用撤销该 sid |
| 前端存储 | 访问 JWT 仅保存在内存；刷新凭证保存在本版 HttpOnly Cookie；不将任一凭证放入 localStorage、sessionStorage、URL 或日志 |
| 迁移方式 | 在现有模块调整，正式 JWT 模式不混合接受旧 Session；不新增同义 API。开发验收使用隔离运行环境，正式切换前不发布中间状态 |

sid 是一条独立登录记录的随机标识，不是用户版本号。它不递增，不与员工上的计数比较，也不使同一账号的其他登录一并失效。jti 同样是随机的单个访问 JWT 标识，不参与用户版本比较。

同一浏览器同一版的多个标签页共享刷新 Cookie，属于同一次浏览器登录；其中一个标签页退出会结束这次登录。其他浏览器的登录及另一版登录独立。

## 2. 现状复核与改动依据

以下表格描述第 1 部分核对的迁移前基线，路径以 C2 根目录为基准；两版结构相同，先列功能版。第 2 部分已拆出账号校验并增加 JWT 基础组件，当前实现状态以上方验证记录为准。

| 当前文件或入口 | 已核对的当前行为 | 迁移决定 |
| --- | --- | --- |
| 迁移前 SessionAuthentication（第4部分已移除） | verify 校验账号密码/员工资格；start 创建 Session、轮换 ID、保存 Long principal；end 使 Session 失效 | 复用密码校验，拆开账号校验与登录凭证生命周期；替换 Session 建立/退出职责 |
| [SecurityConfiguration](../../backend-func/src/main/java/authorization/SecurityConfiguration.java) | 使用 HttpSessionSecurityContextRepository、HttpSessionCsrfTokenRepository；路由授权及有效员工过滤已存在 | 保留路由保护、错误结构和员工检查；替换认证恢复及 CSRF 存储，业务链不创建/读取认证 Session |
| [CurrentEmployeeService](../../backend-func/src/main/java/authentication/CurrentEmployeeService.java) | 通过可信 ID 读取最新员工；要求 enabled 且有组织归属 | 保留；只从已验证的身份适配层接收 ID |
| [AuthController](../../backend-func/src/main/java/authentication/AuthController.java) | 登录返回身份/工作区；自身接口接收 @AuthenticationPrincipal Long；退出依赖 Session | 保留业务入口与自身查询语义，调整登录/退出协作，增加刷新入口 |
| [CurrentSessionVO](../../backend-func/src/main/java/authentication/vo/CurrentSessionVO.java) | 实际承载 employee、workspaces、defaultWorkspaceKey 和可选 authorization | me 的 JSON 结构保留；该类名并不表示必须保留 HttpSession，不为改名制造无关变更 |
| 原SessionStoreFailure（已重命名为[AuthenticationStoreFailure](../../backend-func/src/main/java/authentication/AuthenticationStoreFailure.java)） | 识别 Redis/Lettuce 异常；安全链和异常处理器使用 SESSION_UNAVAILABLE | 复用异常识别原则，调整为认证存储故障；移除依赖 SessionRepositoryFilter 顺序的外层拦截 |
| [AuthorizationService](../../backend-func/src/main/java/authorization/AuthorizationService.java) 与 [CurrentAuthorizationService](../../backend-func/src/main/java/authentication/CurrentAuthorizationService.java) | 基于可信员工实时计算授权；包含继承、组织策略、系统准入 | 保留；JWT scope/roles 不替代现有授权 |
| [前端 api.ts](../../frontend/src/api.ts) | fetch 使用同源 Cookie；每次写操作先取 Session CSRF | 统一添加 Bearer；认证 Cookie 入口单独处理 CSRF；增加刷新协调 |
| [前端 App.vue](../../frontend/src/App.vue) | 登录/退出/启动时 me；工作区跳转使用 location.assign/replace | 加入内存凭证恢复；整页跳转后通过刷新再读取 me |
| [application.yaml](../../backend-func/src/main/resources/application.yaml)、[POM](../../backend-func/pom.xml) | Spring Session Redis、30m timeout、分版 Session Cookie 和 namespace | 移除 Session 专用依赖/配置；保留 Redis 客户端；增加 JWT 和登录记录配置 |
| [部署 stack](../../部署/stack.yaml)、[容器入口](../../部署/backend-entrypoint.sh)、[网关](../../部署/nginx.conf) | 分版 Session namespace、Redis 凭据、/func 和 /oo 去前缀转发 | 调整认证参数及密钥挂载；保留两路代理及 Redis 服务，检查 Bearer/Cookie 透传与日志脱敏 |

对象版对应文件位于 backend-oo；角色图、申请领域模型与端口保留。现有 CloneFactory 使用规范不变；新增凭证响应使用明确的不可变 VO，不给不相似对象强行增加复制层。

## 3. 身份与授权的目标流程

1. 登录沿用账号密码、现有 PasswordEncoder 和员工资格规则。先形成身份/工作区响应，再创建登录状态和签发凭证；失败不返回成功登录结果。
2. JWT 验签及必要字段校验成功后，核对 sid 对应的 Redis 登录记录存在、尚未到期、员工一致。记录缺失时拒绝，不因为 JWT 签名正确而跳过撤销检查。
3. 将验证后的身份转换为现有 Long employeeId principal。继续调用 CurrentEmployeeService 检查最新员工状态。
4. Controller/Service 仍通过现有授权入口执行系统准入、动作权限、组织策略与数据范围判断。API 权限预查询不能替代目标业务操作授权。
5. SecurityContext 只用于当前请求，不再持久化到 HttpSession。业务链使用 STATELESS，不以 Cookie 或请求参数覆盖 Bearer 身份。

可信身份只能来自已验证的 JWT；仅 Base64 解码、任意 sub/employeeId 参数或客户端传入角色都不能成为身份依据。Spring 默认 JWT principal 是 Jwt，不是 Long，必须实现明确的身份转换。

### 3.1 JWT 字段与校验

| 字段 | 约定 |
| --- | --- |
| header.alg | 固定允许 RS256，不接受 none 或令牌自选的其他算法 |
| header.typ | 使用 JWT；刷新凭证不是 JWT，不能进入访问 JWT 解码器 |
| iss | 分版固定配置并精确匹配；建议 func 为 urn:ooad-rbac:c2:func，oo 为 urn:ooad-rbac:c2:oo |
| aud | 分版固定接收方；建议 rbac1-func / rbac1-oo，不把现有业务 systemCode 自动视为 aud |
| sub | 员工主键的十进制字符串；必须可解析为正 Long，且与登录记录归属一致 |
| sid | 至少 128 位安全随机登录标识；每次独立登录重新生成 |
| jti | 至少 128 位安全随机访问令牌标识；每次签发重新生成 |
| iat、exp | 必须存在；exp = min(签发时间 + 15 分钟, 登录绝对到期时间)；iat 不得异常超前、exp 必须晚于 iat |
| nbf | 不额外制造需求；若存在仍须按时间窗口校验 |

只信任配置的验签公钥，不按令牌提供的 URL 获取密钥。私钥不得进入源码、镜像层、前端或日志。两版使用独立密钥；同版的所有部署副本使用同一组配置，不允许每次启动随机生成密钥造成重启/副本间失效。

允许至多 30 秒的 JWT 时钟偏差，但 Redis 登录上限不因此延长；达到登录绝对到期即拒绝。依赖数据库/Redis 实时查询，因此不宣称认证完全无状态或脱离后端即可判断全部权限。

本轮通过显式公钥配置 JwtDecoder，并校验 iss/aud；不只填写 issuer-uri 却遗漏发现端点。未来其他系统如需本地验签，另行约定公钥/JWKS 分发、受众与撤销检查；本轮不虚构已具备 OIDC 元数据服务。

## 4. HTTP 契约

业务路径仍为 /api/...；浏览器沿用 /func/api/...、/oo/api/...。现有 operationId 不变，新增 refreshLogin；不新增 /v1、/v2 或一组平行同义路径。

### 4.1 认证入口

| 方法与路径 | 输入及凭证 | 成功响应 | 说明 |
| --- | --- | --- | --- |
| GET /api/auth/csrf | 无需登录；同源 Cookie | 200 {token}，设置本版 CSRF Cookie | 保留辅助入口，改为非 HttpSession 的 Cookie CSRF 存储 |
| POST /api/auth/login | 原 LoginDTO JSON；CSRF Cookie + X-CSRF-TOKEN | 200 身份/工作区 + accessToken、tokenType、expiresIn；设置刷新 Cookie | 账号校验方式不变；不写 Session Cookie |
| POST /api/auth/refresh | 无业务请求体；刷新 Cookie + CSRF | 200 {accessToken,tokenType,expiresIn}；轮换刷新 Cookie | 无需未过期访问 JWT；不接收 body/query 的刷新凭证别名 |
| POST /api/auth/logout | 无业务请求体；刷新 Cookie + CSRF | 204，撤销当前登录并清除认证 Cookie | 允许访问 JWT 已过期时退出；不要求访问 JWT；无/未知刷新 Cookie 时幂等清理并 204 |

以上认证入口不发送、不依赖 Authorization 请求头；身份查询及其他受保护业务接口只接受 Bearer。前端按明确路径区分，不能让过期访问 JWT 拦截刷新或让刷新 Cookie 授予业务访问权。GET /api/auth/me 及两类权限检查不属于 Cookie 认证入口。

登录保持现有字段并增加凭证字段，避免同时改造工作区协议。例如以下 token 为占位说明，不是可用凭证：

```json
{
  "employee": {"id": 101, "username": "example-user", "name": "示例员工", "organizationId": 2},
  "workspaces": [],
  "defaultWorkspaceKey": null,
  "accessToken": "<signed-access-jwt>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

刷新成功响应只返回凭证，不复制身份/权限查询。expiresIn 按实际剩余有效秒数返回，临近 8 小时上限可小于 900；客户端不得假定每次刷新总有完整 15 分钟。两个响应均不得包含 refreshToken，刷新凭证仅通过 Set-Cookie 交付。

所有认证、身份、权限和目录响应继续使用 Cache-Control: no-store。登录拒绝未知账号、错误密码和不可登录员工时仍统一 401 INVALID_CREDENTIALS。登录表单及顶部退出入口沿用现有页面。

logout 只在刷新凭证确实属于该登录（当前摘要或可识别的已消费摘要）时撤销；不能仅凭可见的 sid 撤销登录。已有刷新 Cookie 但认证存储不可用时返回 503，不伪装为已完成服务端撤销。业务写入与撤销并发时，不承诺中断已经完成认证、进入执行的在途事务。

### 4.2 其余八项能力如何衔接

| 原八项编号 | 接口 | JWT 迁移后的变化 |
| --- | --- | --- |
| ID-01 | POST /api/auth/login | 按上节增加访问凭证，不改账号输入 |
| ID-02 | POST /api/auth/logout | 改为撤销当前登录记录，过期访问 JWT 不阻止退出 |
| ID-03 | GET /api/auth/me | 改用 Bearer；systemCode、身份/工作区和可选授权结构保留；不返回访问令牌 |
| ID-04 | GET /api/auth/me/permission-check | 改用 Bearer；参数、有效权限计算和 allowed 语义保留 |
| ID-05 | GET /api/departments | 改用 Bearer；原目录/树、搜索分页和可见范围保留 |
| ID-06 | GET /api/auth/me/api-check | 改用 Bearer；系统/API 定位、固定 ALL 和实际授权边界保留 |
| ID-07 | GET /api/employees | 改用 Bearer；部门筛选、搜索分页、最小字段和数据隔离保留 |
| ID-08 | GET /api/employees/{employeeId} | 改用 Bearer；仍要求管理员资格与目标可见范围 |

GET /api/business-systems、管理写入、申请接口和其他受保护路由同样切换 Bearer，不另写第二套权限判断。原八项之外仅增加刷新辅助入口；保留已有 CSRF 与系统选择辅助入口。

### 4.3 错误行为

错误体继续为 {code,message}。未认证先于业务参数/数据查询；CSRF 保护的认证写入口仍可能先返回 CSRF 错误。JWT 验签/格式错误应在访问 Redis 前拒绝，避免将坏凭证和存储故障混为一谈。

| 场景 | HTTP / code | 前端行为 |
| --- | --- | --- |
| 业务请求无 Bearer、无效签名/算法/字段、错误 iss/aud | 401 AUTHENTICATION_REQUIRED | 不自动刷新伪造或错误接收方令牌；清理本地身份并提示重新登录 |
| 签名及必要字段有效，仅访问 JWT 到期 | 401 ACCESS_TOKEN_EXPIRED | 最多协调刷新一次，再重试原请求一次 |
| 登录记录不存在/已撤销/超绝对期限，员工停用或无组织 | 401 AUTHENTICATION_REQUIRED | 不循环刷新；结束本地登录展示 |
| 登录凭据无效 | 401 INVALID_CREDENTIALS | 保留统一登录失败提示 |
| 刷新 Cookie 缺失/格式错/未知摘要/到期 | 401 REFRESH_TOKEN_INVALID | 清理本地身份；不撤销仅凭 sid 指向的其他合法记录 |
| 确认已消费刷新摘要被重用 | 401 REFRESH_TOKEN_REUSED | 原子撤销该登录；清理并重新登录 |
| Cookie 认证入口 CSRF 无效 | 403 CSRF_INVALID | 重新领取 CSRF；不盲目重复刷新请求 |
| Redis 登录/刷新存储故障 | 503 AUTHENTICATION_UNAVAILABLE | 报服务不可用；不作为无权限、不清空有效刷新 Cookie、不放行 |
| 授权数据库故障 | 503 AUTHORIZATION_UNAVAILABLE | 沿用授权不可用提示，不能转成 allowed=false |
| 业务参数、未知系统/权限/API、越权和不存在数据 | 沿用现有 400/403/404 及业务 code | 不触发刷新或重试业务操作 |

ACCESS_TOKEN_EXPIRED 只在没有其他 JWT 验证失败时返回。通过 Spring 验证器的结果分类实现，不信任未验签 exp。401 业务认证响应保留 Bearer challenge，正文继续使用现有 ApiErrorVO；不泄露详细签名或密钥错误。

正式切换后 SESSION_UNAVAILABLE 不再作为 JWT 认证存储错误码；旧 Session 批次记录和历史证据不回改。

## 5. Redis 登录记录与严格刷新

保留 spring-boot-starter-data-redis 与 Redis 服务，移除 spring-session-data-redis。认证记录命名空间建议 rbac1:func:auth / rbac1:oo:auth，和历史 Session key 隔离。

每条登录记录包含 employeeId、createdAt、绝对 expiresAt、当前刷新摘要，并保存仍在该登录生命周期内的已消费刷新摘要以识别重用。刷新凭证可由 sid 和至少 256 位安全随机 secret 组成；只保存完整凭证的 SHA-256 摘要，明文仅交付浏览器 Cookie。随机高熵凭证与用户密码不同，不改变现有密码编码策略。

状态变更要求：

1. 创建：生成新的独立 sid，TTL 截止本次登录的 8 小时绝对期限。登录同一浏览器已有有效刷新 Cookie 时，成功替换登录应撤销该 Cookie 所属旧登录；登录失败不得先撤销原登录。其他浏览器记录保留。
2. 业务访问：核对记录存在、employeeId 匹配和绝对期限；不因为访问而延长 TTL。
3. 刷新：在一项 Redis 原子操作中核对当前摘要、标记旧摘要已消费、保存新摘要，保留原绝对到期时间。先检查最新员工资格，再签发新访问令牌；结果不可确定时不得假装成功或自动反复提交旧凭证。
4. 重用：只有匹配已消费摘要才撤销该 sid；完全未知 secret 只拒绝，不撤销。否则攻击者仅知道 sid 就能使他人退出。
5. 退出：校验凭证归属后撤销该 sid，使该登录此前签发的所有 JWT 在后续认证时失效；不按 employeeId 删除所有登录。
6. 到期：登录及相关已消费摘要统一自然过期。不批量清理历史 Session key，让旧 key 按原 TTL 到期；正式 JWT 链不再读取它们。

原子性以同一 Redis 操作/脚本覆盖必要记录为验收要求；如采用多个 key，按 sid 使用同一 hash tag 便于将来迁移 Redis Cluster。本轮不假定当前单实例 Redis 已提供故障切换/线性一致性保证。Redis 丢失登录记录会要求重新登录，禁止“缓存未命中即重建有效登录”。

不新增 tokenVersion、employee.authVersion、全账号递增计数器或每请求比较用户版本的方案；登录撤销始终针对随机 sid 记录。角色及组织权限修改依然由实时授权生效，无需修改 JWT 或用户版本号。

## 6. Cookie、CSRF 与前端接入

| 用途 | func 建议配置 | oo 建议配置 |
| --- | --- | --- |
| 刷新 Cookie 名 | RBAC1_FUNC_REFRESH | RBAC1_OO_REFRESH |
| CSRF Cookie 名 | RBAC1_FUNC_CSRF | RBAC1_OO_CSRF |
| Cookie Path | /func/api/auth/ | /oo/api/auth/ |
| 刷新属性 | HttpOnly、SameSite=Strict、生产 Secure、host-only、Max-Age 不超过登录剩余期限 | 同左 |
| CSRF | CookieCsrfTokenRepository；GET csrf 以 JSON 提供 token，写入口使用 X-CSRF-TOKEN；不依赖 HttpSession | 同左 |

CSRF Cookie 可保持 HttpOnly，因为前端通过已有 JSON 入口取 token，无需读取 document.cookie。生产同源代理和本机同源开发代理均需核对 Path；网关去掉 /func、/oo 后，后端设置的 Cookie Path 仍以浏览器看到的公开路径为准。直接访问后端端口的测试需覆盖显式路径配置，不能凭默认路径假定 Cookie 会发送。

登录、刷新、退出继续防 CSRF；纯 Bearer 业务请求不依赖自动携带的认证 Cookie，不再为每次业务写入调用 GET csrf。Cookie 入口不允许跨源读取 CSRF token；不开放 CORS，不用“带了 Cookie 就认为同源”的判断替代 CSRF。登录成功和退出后按 Spring Security 生命周期清理/重取 CSRF。

前端实施要求：

- api.ts 维护单一认证请求入口及内存访问令牌；同一页并发过期请求共享一次刷新，不为每个请求各刷一次。
- 同版标签页间协调 Cookie 刷新/登录/退出；浏览器若缺少必要协调能力，必须明确降级到重新登录，不悄悄并发轮换。跨标签页通信不把凭证持久化到 Web Storage。
- 页面启动及工作区整页跳转后，先用刷新 Cookie 恢复访问令牌，再调用 me；刷新失败则显示原登录页，503 保留可重试的故障提示。
- 仅对 ACCESS_TOKEN_EXPIRED 进行一次刷新和一次重试；不对 403、业务错误、网络中断或未知执行结果重复提交写操作。页面初始化时没有访问令牌的恢复不属于业务重试。
- 刷新请求不做透明网络重试。严格轮换下，两个相同旧凭证并发或响应丢失后再次提交旧凭证可能导致整次登录撤销，这是 Q3 已接受的取舍，必须测试和记录。
- 不因 JWT 载荷中的角色/权限控制页面；仍读取已有 me 授权结果，菜单/按钮控制之外后端继续执行实际授权。

客户端退出失败时不能显示“服务端已退出”；先报告故障并允许用户重试，避免本地清空被误认为已撤销服务端凭证。退出成功后清除内存访问令牌，通知同版标签页并回到现有登录页。

## 7. 模块与配置迁移清单

| 部分 | 保留 | 调整/新增 | 退出正式认证链路 |
| --- | --- | --- | --- |
| authentication | LoginDTO、PasswordEncoder、账号仓储、CurrentEmployeeService、工作区/自身授权 | 账号凭据校验职责、JWT 编解码/可信身份转换、登录生命周期服务、Redis 登录仓储、凭证 VO、refresh | SessionAuthentication 的 getSession/changeSessionId/saveContext/invalidate 职责 |
| authorization | 管理路由保护、员工资格检查、AuthorizationService | Bearer 支持、无认证 Session 的 Security 配置、统一 401/403/503 | HttpSessionSecurityContextRepository、基于 SessionRepositoryFilter 顺序的异常包装 |
| contract | ApiErrorVO、业务错误与校验机制 | 凭证过期/刷新/认证存储错误映射 | JWT 模式下的 SESSION_UNAVAILABLE 映射 |
| csrf | GET csrf 路径、X-CSRF-TOKEN、认证写保护 | Cookie CSRF 存储、分版 Cookie 属性与错误优先级 | HttpSessionCsrfTokenRepository |
| frontend | 登录表单、页面、工作区、权限展示、部门员工与申请流程 | 内存 Bearer、刷新/恢复/并发协调、失败处理 | 依赖 Session Cookie 的身份恢复、业务写入逐次 Session CSRF |
| dependencies | starter-security、starter-data-redis、数据库及 CloneFactory | spring-boot-starter-oauth2-resource-server，由现有 Boot 管理版本 | spring-session-data-redis |

配置目标（变量名在实现前保持本文一致）：

| 配置 | 目标 |
| --- | --- |
| RBAC_DB_*、RBAC_REDIS_* | 保留既有连接/凭据配置 |
| RBAC_SESSION_COOKIE_NAME/PATH/SECURE、RBAC_SESSION_TIMEOUT/NAMESPACE | 正式 JWT 模式退出；不能悄悄解释成 JWT 配置 |
| RBAC_JWT_ISSUER、RBAC_JWT_AUDIENCE | 分版精确值，启动时校验非空 |
| RBAC_JWT_PRIVATE_KEY_LOCATION、RBAC_JWT_PUBLIC_KEY_LOCATION | 外部 PEM 资源位置，生产用只读 secret 挂载；不得输出内容 |
| RBAC_JWT_ACCESS_TTL | 默认 15m，不超过登录绝对剩余时间 |
| RBAC_LOGIN_TTL | 默认 8h，绝对到期，刷新和业务访问均不续期 |
| RBAC_AUTH_NAMESPACE | 默认 rbac1:func:auth / rbac1:oo:auth |
| RBAC_REFRESH_COOKIE_NAME、RBAC_CSRF_COOKIE_NAME | 分版固定缺省值，明确覆盖规则 |
| RBAC_AUTH_COOKIE_PATH、RBAC_AUTH_COOKIE_SECURE | 公开认证路径；本机 HTTP 可 false，生产 HTTPS 必须 true |

不引入 RBAC_AUTH_MODE 之类的长期 Session/JWT 双模式开关；同一正式构建只按定稿 JWT 契约执行。隔离测试配置仅控制端口、测试数据库、Redis 命名空间、测试密钥和 Cookie 路径，不引入一套平行业务实现。

MySQL 员工、密码、角色、继承关系、组织策略和 API 映射不需要因 JWT 新增表或字段；本方案不增加新的 Flyway 迁移。Redis 新键不属于 MySQL 数据迁移。已有 Session 不转为 JWT，切换后用户重新登录。回退需前后端成套回退并重新登录，不承诺凭证兼容。

## 8. 六部分实施与验收

| 部分 | 交付与改动 | 验收门槛 |
| --- | --- | --- |
| 1 方案与契约 | 本文、进度入口、实际文档检查证据；Q1/Q2/Q3 和不用 tokenVersion 已确认 | 契约可审查，源码/依赖/正式 OpenAPI 不被提前改成 JWT |
| 2 后端 JWT 身份入口 | 两版凭据校验整理、编解码与身份转换、过滤链、访问凭证响应；在隔离配置中开发 | 正确凭证被识别，伪造/过期/跨版/错误字段被拒绝，实际权限不由 JWT 声明决定 |
| 3 登录生命周期 | Redis 状态、原子刷新、重用撤销、退出和故障处理 | 当前登录立即失效、多浏览器隔离、8 小时绝对到期、存储故障不放行；第 2/3 部分完成前不对外切换 |
| 4 前端与旧 Session 退出 | 统一 Bearer、页面恢复、跨标签页协调、CSRF、移除旧 Session 依赖及部署配置 | 页面/整页工作区跳转正常；旧 Cookie 不再认证；浏览器两版互不影响 |
| 5 完整回归 | 共用 HTTP、差异设计测试、浏览器、覆盖率和原始输出 | 两版一致，保留八项能力、管理/申请/原表和安全边界；证据归档 C2 测试 |
| 6 文档交付 | 主 OpenAPI、八项契约、模块/运行说明、部署 secret 说明、相关图件与测试记录；通过后同步 Apifox | 不把本机证据当集群验收；列出队友部署和跨项目联调待办 |

不在第 2 部分临时引入“签名正确就放行”的另一套认证，最终请求链必须同时完成第 3 部分登录状态核对。第 2 部分可先对编解码/身份转换做隔离测试，不发布功能不完整的中间构建。

### 8.1 测试必须覆盖的边界

1. JWT：缺失/损坏、无效签名、禁止算法、错误 iss/aud、缺失字段、非正整数 sub、sid 归属不符、未来 iat、有效期和时钟边界；两版令牌不得交叉认证。
2. 生命周期：15 分钟、8 小时上限、刷新不续上限、多次刷新、过期/退出、两个浏览器独立、同版标签页共享登录、账号停用/无归属；旧 Session Cookie 不能认证。
3. 刷新：原子并发、确认旧摘要重用撤销、未知 secret 不撤销、严格重用下网络响应丢失取舍、退出/刷新竞争和新登录替换；不能只测串行成功路径。
4. CSRF/Cookie：登录/刷新/退出缺 token 或错误 token、跨版 Cookie、过期访问 JWT 不妨碍刷新/退出、公开 Path/HttpOnly/SameSite/Secure、代理和直连测试配置。
5. 故障：Redis 连接故障/超时/丢记录、授权数据库故障、密钥缺失/错误/重启稳定性；故障与正常拒绝区分，凭证不得出现在日志或错误正文。
6. 授权：自身与越权、直接/继承权限、撤角色/撤边、组织停用和同源重授、API ALL/未知 API、部门树/搜索分页/最小字段/数据隔离；撤权提交后下次请求生效。
7. 浏览器：登录、刷新、整页跳转、并发 401、多标签页、退出广播、503 提示、禁止对未知执行结果重放写操作、工作区/管理/申请和原表回归。

测试统一放在 [测试目录](../../测试/归档/测试说明-整理前.md)，沿用共同 HTTP 场景和运行器；新结果独立归档，不覆盖 [Session 最终验收](../../测试/归档/最终验收.md)。只有实际运行后才记录通过数、JaCoCo、PNG 和命令 TXT。本步实际检查见 [JWT 第一步验证记录](../../测试/归档/契约/JWT第一步验证记录.md)。

## 9. 使用方式、待完成与后续联调

源码前后端和部署已接入，Web启动需要外部密钥；[后端运行](../../测试/归档/JWT登录生命周期验证.md)、[前端协作](前端说明-整理前.md)及[部署配置](../../部署/归档/部署说明-整理前.md)给出使用方式。本机运行服务后续已成套切换。浏览器保持原登录页面；调试客户端先领取CSRF、携带Cookie登录，再对业务调用设置Authorization: Bearer；刷新/退出只使用该次登录Cookie与CSRF。令牌示例仅说明字段，不能作为实际凭证。

两版JWT、Redis登录、前端内存凭证与同版SharedWorker、部署secret已实现；当前第4/5部分结果以[整体验收记录](../../测试/归档/JWT前端与整体验收.md)为准。第6部分已统一49图及Apifox49接口/44模型同步回读，本机JWT服务已切换，课程集群仍待部署。课程Swarm由队友部署，项目系统联调仍单列待办。

未来联调时，对方只验 JWT 公钥不能获知本地 Redis 登录已撤销，也不能替代 C2 的动态 RBAC1 授权；需要另定撤销查询/网关校验、系统身份与受众、权限与对象数据范围、网络和错误协议。JWT 不自动等于单点登录。优化方向是基于真实联调需求再考虑密钥轮换/JWKS、认证中心和部署可用性，不提前增加无调用抽象。

技术依据：[Spring Security 6.5 JWT](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)（验签、principal、issuer/audience 与定制转换）、[Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html)（Cookie 存储与保护范围）、[RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14)（借鉴刷新轮换及重放检测，不宣称本项目已经实现 OAuth2 协议）。
