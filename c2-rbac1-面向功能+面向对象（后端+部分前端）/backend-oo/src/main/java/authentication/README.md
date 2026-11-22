# 认证、独立登录状态与工作区

## 当前实现

两版后端使用 Spring Security Bearer JWT + Redis 独立 sid 登录记录。账号密码验证、登录生命周期、HTTP Cookie 与业务授权分开；继续使用原 PBKDF2、员工资格、工作区和实时 RBAC1 授权。不使用 tokenVersion、用户版本字段或全账号递增计数器。

第3部分的后端实现及真实测试见 [生命周期验证](../../../../../测试/README.md)，契约见 [JWT方案](../../../../../docs/项目总结.md) 和 [主OpenAPI](../../../../../api/openapi.yaml)。第4部分已接入内存JWT与同版SharedWorker协调，并完成部署密钥挂载；第4/5部分进度及证据见[前端与整体验收](../../../../../测试/README.md)。现有旧运行服务没有替换。

## 文件分工

| 文件 | 职责及协作 |
| --- | --- |
| [AuthController](AuthController.java) | 登录/刷新/退出、CSRF、自身身份/权限和系统列表；准备工作区后调用生命周期，不从请求参数选择自身身份 |
| [AccountAuthentication](AccountAuthentication.java) | 账号密码校验及有效员工资格；未知账号执行备用哈希验证，统一失败响应 |
| [AuthenticationConfiguration](AuthenticationConfiguration.java) / [AuthenticationProperties](AuthenticationProperties.java) | 配置绑定、UTC时钟、外部PEM密钥和Cookie CSRF；非Web初始化不读取JWT密钥 |
| [LoginAuthentication](LoginAuthentication.java) | 创建sid、准备JWT、提交Redis登录、严格刷新和当前登录撤销；成功前不发布凭证 |
| [RedisLoginStore](RedisLoginStore.java) / [登录状态Lua](../../resources/authentication/login-state.lua) | 原子检查、消费旧摘要、重用撤销、归属校验和绝对期限；同sid使用同一hash tag |
| [RefreshCredential](RefreshCredential.java) | sid加256位随机secret，规范解析及SHA-256摘要；Redis不接收原始值 |
| [JwtAccessTokens](JwtAccessTokens.java) | Spring/Nimbus RS256签发验签、原始sub类型、issuer/audience及时间校验 |
| [JwtEmployeeAuthentication](JwtEmployeeAuthentication.java) | 强制在线登录记录和最新员工检查，返回Long principal；不信任JWT roles/scope作为业务权限 |
| [AuthCookies](AuthCookies.java) | 分版HttpOnly刷新Cookie的读取/写入/清理；重复同名Cookie按无效处理 |
| [AuthenticationFailure](AuthenticationFailure.java) / [AuthenticationStoreFailure](AuthenticationStoreFailure.java) | 区分确定性401与认证存储503，不泄露凭证或存储记录 |
| [CurrentEmployeeService](CurrentEmployeeService.java) | 依据可信ID读取最新员工，要求启用且有组织归属；不建立凭证或计算权限 |
| [CurrentAuthorizationService](CurrentAuthorizationService.java) | 自身角色来源/有效权限和两类预查询，复用原授权计算 |
| [WorkspaceService](WorkspaceService.java) | 可进入的管理/业务工作区与默认导航，不替代动作授权 |
| [LoginAccount](LoginAccount.java) / [LoginAccountRepository](LoginAccountRepository.java) / [PasswordConfiguration](PasswordConfiguration.java) | 员工账号、PBKDF2及仓储；原密码策略不变 |
| [DemoLoginAccounts](DemoLoginAccounts.java) | 显式演示开关与--init-demo，保留原合成账号初始化，不开放任意员工默认密码 |
| [LoginResponseVO](vo/LoginResponseVO.java) / [AccessTokenVO](vo/AccessTokenVO.java) / [CurrentSessionVO](vo/CurrentSessionVO.java) | 登录在原身份字段上增加访问凭证；刷新仅三字段；me保留旧身份结构。toString不输出凭证 |

## 核心流程与失败边界

登录先验证账号、读取最新员工并准备工作区，再准备JWT、建立独立Redis登录记录；只有整个流程成功才返回访问JWT和刷新Cookie。同浏览器已有可证明归属的刷新凭证时，成功替换登录会撤销该旧sid，其他浏览器不受影响；失败的密码校验不先撤销原登录。新记录建立后若旧记录撤销失败，会尝试撤销未发布的新记录并返回故障。

刷新先验证凭证格式和摘要，读取所属登录并核对最新员工。签发准备成功后，Lua原子核对当前摘要、记录已消费摘要、保存新摘要，并保留原绝对到期。两次并发提交同一凭证只有一次成功，另一次确认重用会撤销整个sid；已成功签发的访问JWT也会在下一次认证时被拒绝。未知secret仅拒绝，不能凭sid撤销他人。刷新网络结果不确定时不自动重试。

业务请求完整验签、校验字段后，先核对Redis记录与employeeId，再读最新员工；员工停用或无归属时撤销当前sid，重新启用不能复活这次已观察到失效的登录。数据库/Redis故障不降级为无权限、allowed=false或验签即放行。角色、继承和组织权限策略仍由原AuthorizationService实时计算。

登录绝对上限8小时，访问JWT最多15分钟且不超过登录期限，刷新/业务访问均不续期。时钟偏差只用于JWT校验，不延长Redis登录期限。退出核验当前或已消费摘要后撤销sid；无/未知刷新Cookie幂等返回204，但存储故障不假报成功。并发撤销不承诺中断已进入执行的事务。

Cookie CSRF仅用于登录、刷新、退出。JSON提供掩码token，Cookie为HttpOnly，写入口使用X-CSRF-TOKEN；业务Bearer无需CSRF。Controller在登录成功和退出时清理CSRF，关闭框架对每次Bearer认证自动清理CSRF的行为，避免并发业务请求破坏刷新。访问JWT已过期不会阻止Cookie认证入口。

## 工作区、授权及运行

管理员优先管理工作区，再加入可进入的业务系统；员工优先部门默认系统，再选父公司默认系统，无可用项允许默认值为空。当前组织模型为公司及直属部门，不扩大为任意深度。GLOBAL/组织管理员的管理资格、数据范围、继承、组织停用/重开/同源重授保持原实现。

Web启动必须提供稳定外部PKCS#8私钥和X.509公钥，两版密钥独立，同版多个副本配置一致；缺失/无效密钥启动失败，不随机生成生产密钥。运行变量见 [本步运行说明](../../../../../测试/README.md)。初始化命令仍显式使用--init-demo或--import-excel，非Web流程不需要JWT私钥。

测试集中在C2测试目录，原65项HTTP业务场景已改为Bearer而非降低授权断言；新增真实Redis并发、JWT生命周期HTTP、Cookie/配置和启动边界。测试临时密钥由运行器在工作区外生成，不归档私钥，不改演示库、不停止共享依赖。
