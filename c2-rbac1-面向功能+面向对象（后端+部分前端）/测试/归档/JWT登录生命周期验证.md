> 历史归档：保留整理前的阶段记录；其中“当前”“待完成”和测试数量均属于原批次。现行操作及状态以[统一说明](../README.md)为准。

# JWT 第3部分：登录生命周期与完整后端安全链

日期：2026-11-21（Asia/Shanghai）。两版源码后端已切换为 Spring Security Bearer JWT + Redis 随机 sid 登录记录，正式本地 OpenAPI 为 2.5.0。前端、部署模板及现有运行服务尚未切换；本步不声称已完成浏览器或课程集群验收。

## 已完成

- 账号校验保持原 PBKDF2、统一失败和可信员工规则；登录返回原身份/工作区以及 accessToken、tokenType、expiresIn，刷新凭证只通过 HttpOnly Cookie 交付。
- Redis 登录记录只保存员工归属、创建/绝对期限和刷新摘要；同 sid 的记录/已消费摘要共用 hash tag。Lua 原子完成校验、严格轮换、重用撤销和当前登录退出。
- 访问 JWT 最长15分钟、登录绝对上限8小时；刷新和业务访问均不续上限。未知 secret 不能凭 sid 撤销他人；确认旧摘要重用后撤销整次登录。
- 同账号多浏览器独立；成功替换当前浏览器登录会撤销它的旧 sid，失败登录不先退出已有登录；退出不影响其他浏览器或另一版。
- 完整安全链先验签/字段，再核对真实 Redis 登录记录及最新员工，最后复用原路由/业务授权。员工失去资格后结束当前 sid，重新启用不能复活这次已观察到失效的登录。
- 认证入口采用 Cookie CSRF，JSON 提供掩码 token，必须用 X-CSRF-TOKEN；Bearer 业务写入不要求 CSRF。登录/退出显式轮换 CSRF，逐请求 Bearer 认证不清理它。过期访问 JWT 不阻止刷新/退出。
- 区分401确定性拒绝、403业务/CSRF拒绝、503认证Redis/授权数据库故障。存储故障不放行、不当作 allowed=false，不清除仍可重试的刷新 Cookie，不假报退出成功。
- 从后端 POM 移除 Spring Session Redis，停止从 HttpSession 恢复身份；无 Session/JWT 双模式开关。旧 SessionAuthentication 已退出组件扫描并标记待第4部分清理；SessionStoreFailure 重命名为 AuthenticationStoreFailure。
- 原65项共同 HTTP 业务测试迁移为显式 Bearer 浏览器夹具，保留角色继承、撤权、组织策略、部门/员工隔离、API映射及申请流程断言；原测试类重命名为 AuthHttpTest，历史结果保留原名称。
- 两版新增配置、Cookie和生命周期说明、源码/模块索引；功能版100、对象版107个生产 Java 文件。没有新增 Flyway 迁移、JWT数据库字段或 tokenVersion／用户版本号。

实现入口：[认证模块](../../backend-func/src/main/java/authentication/README.md)、[安全链](../../backend-func/src/main/java/authorization/SecurityConfiguration.java)、[状态脚本](../../backend-func/src/main/resources/authentication/login-state.lua)、[共同HTTP回归](../后端/common/java/contract/AuthHttpTest.java)、[生命周期HTTP](../后端/common/java/authentication/JwtLoginHttpTest.java)、[真实Redis](../后端/common/java/authentication/RedisLoginStoreTest.java)。对象版同路径在 backend-oo，外部行为一致。

## 关键实现与实际修正

登录先准备身份/工作区和签名结果，建立新登录并完成旧浏览器登录撤销后才对外发布凭证；若替换旧登录时存储失败，尝试清理未发布的新记录。刷新先证明凭证归属并读取最新员工，准备签名后才原子轮换。数据库或签名准备失败不先消费旧刷新凭证。若员工查询与签名之间刚跨绝对期限，明确返回401 REFRESH_TOKEN_INVALID并结束过期登录，不误报500。

严格轮换下，两个相同旧凭证并发只允许一次成功，另一请求确认重用后撤销该 sid；成功请求刚收到的 JWT 也会在下一次认证时失效。前端第4部分必须协调刷新，且不能对结果不确定的刷新/业务写入盲目重试。

HTTP首轮发现两处待修正：登录增加凭证后，me应只比较原身份字段；Spring CSRF 默认认证策略会在Bearer认证后清除CSRF，造成合法刷新403。前者修正契约断言，后者由Controller显式管理登录/退出CSRF，禁止逐请求Bearer清理。修正后同一CSRF经业务GET再刷新仍成功。

首次本地OpenAPI校验还发现组件插入缩进范围错误；定位为文档生成位置，修正后重验整个规范、八项快照和示例。该错误不是已通过的后端运行失败。

## 实际测试结果

| 批次 | 实际结果 | 范围 |
| --- | --- | --- |
| 首次编译 / 编译复核 | 编译失败，执行0项 | 修正脚本资源装配API和旧目录夹具的身份类型；不冒充运行期RED |
| 仓储与基础 | 72项通过，零失败/错误/跳过 | 当时的63项基础及9项真实Redis测试 |
| HTTP首轮 | 78项，2失败、0错误/跳过 | 上述响应兼容和CSRF问题；输出保留 |
| HTTP边界复核 | 24项通过，零失败/错误/跳过 | 14项JWT HTTP、1项身份兼容、9项Provider |
| 完整-func / 完整-oo | 190 / 194项通过 | 启动配置测试补齐前的完整批次，单独保留 |
| 最终-func / 最终-oo | 192 / 196项通过，失败/错误/跳过均0 | 补齐启动边界的批次，单独保留 |
| 到期边界 | 3项通过，零失败/错误/跳过 | 签名前跨绝对期限、签名失败不消费、Bearer不能绕过Cookie入口CSRF |
| **交付-func / 交付-oo** | **194 / 198项通过，失败/错误/跳过均0** | 原有业务/迁移/设计回归、63项JWT基础及本步新增43项 |

本步新增43项由真实Redis10项、JWT生命周期HTTP14项、凭证/Cookie/配置15项、Web/非Web启动2项和刷新准备边界2项组成。每版共有79项HTTP（原65＋新增14）、2项迁移；两版差异测试保持原设计。Redis并发在真实服务执行；HTTP依赖故障用明确Spy注入，不停止共享服务。

| 最终 JaCoCo | 功能版 | 对象版 |
| --- | --- | --- |
| 行 | 1717 / 1802，95.28% | 1744 / 1828，95.40% |
| 分支 | 642 / 771，83.27% | 645 / 775，83.23% |

报告包含尚待清理的旧 SessionAuthentication 和启动入口等未执行部分，不为覆盖率编造调用。定向批次覆盖率不代替最终完整覆盖率。

证据目录：[JWT登录生命周期-20261121](../结果/JWT登录生命周期-20261121)。最终原始结果：[功能版JSON](../结果/JWT登录生命周期-20261121/交付-func/结果.json)、[对象版JSON](../结果/JWT登录生命周期-20261121/交付-oo/结果.json)；各自目录保留命令TXT、原始输出、Surefire XML及JaCoCo HTML/XML。本步静态核对、截图和交付摘要也归档在该目录，不覆盖旧Session和第2部分证据。

## 运行与调试

正常 Web 启动需要外部 PKCS#8 RSA 私钥及 X.509 公钥；配置 RBAC_JWT_PRIVATE_KEY_LOCATION、RBAC_JWT_PUBLIC_KEY_LOCATION，例如 file:/绝对路径/private.pem 和 file:/绝对路径/public.pem。两版分别配置不同密钥；同一版所有副本共享稳定密钥，禁止每次启动重新生成。缺失/无效密钥启动失败；非Web的初始化/导入不读取私钥。

保留原 RBAC_DB_*、RBAC_REDIS_*。新参数：RBAC_JWT_ISSUER、RBAC_JWT_AUDIENCE、RBAC_JWT_ACCESS_TTL（15m）、RBAC_LOGIN_TTL（8h）、RBAC_AUTH_NAMESPACE、RBAC_REFRESH_COOKIE_NAME、RBAC_CSRF_COOKIE_NAME、RBAC_AUTH_COOKIE_PATH、RBAC_AUTH_COOKIE_SECURE。默认分版值见 [功能版配置](../../backend-func/src/main/resources/application.yaml)、[对象版配置](../../backend-oo/src/main/resources/application.yaml)。旧 RBAC_SESSION_* 不再提供认证配置。

同源代理公开Cookie路径分别为 /func/api/auth/ 和 /oo/api/auth/；后端去前缀后仍按公开路径设置。直接访问后端的独立测试可显式覆盖 RBAC_AUTH_COOKIE_PATH=/，不能把这个测试覆盖值直接当生产配置。HTTPS部署必须启用Secure。

调试顺序：GET /api/auth/csrf保存Cookie与JSON token；POST登录携带Cookie、X-CSRF-TOKEN和原JSON凭据；业务调用使用Authorization: Bearer；刷新和退出使用本版刷新Cookie和重新取得的CSRF。刷新不从JSON/query读取凭证。旧Session Cookie不能换取JWT，迁移后重新登录。

在 C2 根目录复现后端测试：

```powershell
python 测试/脚本/run_backend.py func --output-dir 测试/结果/JWT登录生命周期-20261121/交付-func --build-dir target/jwt-lifecycle
python 测试/脚本/run_backend.py oo --output-dir 测试/结果/JWT登录生命周期-20261121/交付-oo --build-dir target/jwt-lifecycle
```

复跑时更换输出目录以保留本批次。运行器源码摘要已覆盖Lua等实际授权资源，并核对JAR内容。运行器现在需要OpenSSL生成每轮独立测试密钥，仅写工作区外临时目录，凭据只进入子进程环境，不把测试或部署私钥归档。仅连接专用_test库和独立认证命名空间；测试Cookie路径显式为/。没有执行clean或批量删除文件。

[交付核对JSON](../结果/JWT登录生命周期-20261121/交付核对.json)、[命令记录](../结果/JWT登录生命周期-20261121/命令记录.txt)、[静态契约](../结果/JWT登录生命周期-20261121/静态契约.txt)、[功能版截图](../结果/JWT登录生命周期-20261121/JaCoCo-func.png)、[对象版截图](../结果/JWT登录生命周期-20261121/JaCoCo-oo.png)。

## 尚未完成与下一步

- 第4部分：前端Bearer、内存凭证、刷新/恢复/跨标签页协调，更新前端测试运行器与部署密钥挂载/环境配置，清理退役Session源码。当前前端与部署模板不适配新后端，不发布这个中间组合。
- 第5部分：完整JWT浏览器、原表及最终整体验收。本步没有新的浏览器通过数，不用旧35项替代。
- 第6部分：JWT相关图件和交付文档收尾，验证后同步Apifox。当前本地OpenAPI为2.5.0，49操作/44模型；Apifox仍保留此前已验证的2.4.0，未提前同步。
- 现有开发服务未重启，未部署课程Swarm、未提交或推送。课程集群由队友部署/验收；跨项目CORS、系统联调和独立认证中心仍另列。

下一步先改前端统一请求层和登录恢复，再验证页面/多标签页，继续沿用随机sid而非用户版本号。
