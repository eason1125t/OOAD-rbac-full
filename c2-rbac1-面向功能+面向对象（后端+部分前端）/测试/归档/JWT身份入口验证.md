> 历史归档：保留整理前的阶段记录；其中“当前”“待完成”和测试数量均属于原批次。现行操作及状态以[统一说明](../README.md)为准。

# JWT 第 2 部分：账号校验与可信身份入口

日期：2026-11-21（Asia/Shanghai）。本文件保留第2部分当时的实现与测试快照；后续第3部分已接入完整后端JWT，当前状态见[生命周期验证](JWT登录生命周期验证.md)，不回改下文历史批次结果。本步在两版后端实现 JWT 基础组件，并将账号校验从 Session 生命周期拆出。重要策略沿用 [已确认契约](../../docs/归档/JWT认证迁移方案.md)，不使用 tokenVersion／用户版本号。

## 本步完成与使用边界

| 实现 | 作用 |
| --- | --- |
| 两版 AccountAuthentication | 保留原 PBKDF2、备用哈希计算和统一失败语义；Controller 直接调用账号校验，不再通过 SessionAuthentication.verify |
| 两版 JwtAccessTokens | Spring/Nimbus RS256 签发验签；显式 issuer/audience、必填 claims、原始字符串 sub、正 Long、随机 sid/jti、时间和密钥校验 |
| 两版 JwtEmployeeAuthentication | 接入 Spring JwtAuthenticationProvider 的转换器；强制先检查登录记录，再读取最新员工，返回原 Long principal；忽略 JWT roles/scope |
| 两版 AccessTokenVO | 访问 JWT、Bearer 类型和真实剩余秒数；不含刷新凭证，toString 不输出访问凭证 |
| 两版 POM | 增加 Boot 管理的 OAuth2 Resource Server starter；本机解析 Spring Security 6.5.11，未手工混用其他安全库版本 |
| 现有认证/日志 | SessionAuthentication 仅管理现有会话；日志测试同时覆盖独立账号校验和 Session 操作，不记录凭据 |

CurrentEmployeeService、RBAC1 授权、组织策略、目录和业务操作算法保持原实现。生产 Java 文件为功能版 92、对象版 99；模块说明和源码索引已同步。

新 JWT 组件目前通过共同测试及 Spring Provider 装配验证，尚未注册到正式 HTTP 安全链，登录接口仍返回原 Session 身份/工作区。没有并行新增一个“验签即放行”的 API，也没有 Session/JWT 双模式开关。第 3 部分必须提供实际 Redis LoginVerifier 与完整生命周期后，才装配正式 JWT 安全链；前端与旧 Session 依赖退出按第 4 部分完成。

当前运行仍按 [C2 运行说明](../../docs/归档/入口说明-整理前.md)，无需设置生产 JWT 密钥即可使用原 Session 开发流程。本步构建物是分阶段验证包，不是已完成 JWT 切换的交付包；没有重启现有开发入口。

## 实现与安全细节

- 使用外部 PKCS#8 私钥/X.509 公钥，至少 2048 位 RSA，检查密钥匹配；不把密钥写入仓库，也不在生产组件内随机生成密钥。两次读取相同 PEM 的实例可验证此前签发的令牌。
- 访问 exp 截止配置访问期限与登录绝对期限的较早值；允许至多 30 秒 JWT 时钟偏差。8 小时登录记录上限由第 3 部分进一步强制，不因验签偏差而延长。
- 原始 payload 的 sub 必须是字符串。使用 Nimbus 只做原始类型拒绝检查，随后仍完整验签、校验 claims；预解析内容不提供可信身份，不手写 Base64 分割或签名算法。
- 缺字段、错误 issuer/audience、错误 sub/sid/jti、异常 iat/nbf/exp 一律拒绝。只有其他检查均通过的到期令牌才产生 ACCESS_TOKEN_EXPIRED，供后续 HTTP 错误映射使用。
- 身份转换的 LoginVerifier 为必需协作者，无默认空实现；其拒绝或存储故障不降级放行。签名无效时不访问登录或员工存储。员工数据库故障继续传播，后续安全链映射 503。
- 不把 JWT claims 里的角色、scope 或权限转换为现有业务 authorities；真实业务继续调用原授权计算。sid 是独立随机登录标识，不是用户递增版本号。UUID 格式检查中的标准 UUID 类型标记不参与用户令牌撤销。

技术依据：[Spring Security JWT 验签、Provider 与 principal 转换](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/resource-server/jwt.html)。

## 实际执行记录

证据目录：[JWT身份入口-20261121](../结果/JWT身份入口-20261121)。运行器读取现有本机 Docker 依赖，把凭据只放入 Maven 子进程环境；只连接 func/oo 对应的专用测试库，迁移用例另用随机后缀测试库。

| 批次 | 实际结果 | 含义 |
| --- | --- | --- |
| RED | 编译失败，执行测试 0 | 先新增共同测试与依赖；失败原因为待实现类不存在，不冒充运行期断言失败 |
| 定向-func | 56 项通过，失败/错误/跳过均 0 | 首轮账号、JWT 与 Provider 定向验证 |
| 完整-func / 完整-oo | 151 / 155 项；各 1 项失败、0 错误/跳过 | 新增 numeric-sub 原始类型边界失败；失败输出完整保留 |
| 边界修正-func | 63 项通过，失败/错误/跳过均 0 | 修正并确认样本 payload 的 sub 确实为 Number，再验证拒绝 |
| 最终-func / 最终-oo | 151 / 155 项通过，失败/错误/跳过均 0 | 两版各新增 63 项账号/JWT/Provider 测试；原 88/92 项全部回归，包含各 65 项共同 HTTP 及各 2 项迁移测试 |

初轮失败表明：在常规 claims 转换回调处检查 sub 时，解码库已将其规范化为字符串。因此把原始 JSON 类型拒绝检查放在完整解码前，仍交由 Spring/Nimbus 确立最终可信性。另补测真实时钟含毫秒时，合法“从当前时间起 8 小时”的登录期限不因取整而被误拒绝。

| 最终覆盖率 | 功能版 | 对象版 |
| --- | --- | --- |
| 行 | 1547 / 1626，95.14% | 1574 / 1652，95.28% |
| 分支 | 546 / 651，83.87% | 549 / 655，83.82% |

最终 JaCoCo、命令 TXT、Surefire XML 和相关结果图均已归档。定向批次覆盖率仅表示该测试选择，不作为全项目覆盖率；失败批次不产生成功覆盖率结论。

- 功能版：[运行结果](../结果/JWT身份入口-20261121/最终-func/结果.json)、[JaCoCo HTML](../结果/JWT身份入口-20261121/最终-func/jacoco/index.html)、[结果截图](../结果/JWT身份入口-20261121/JaCoCo-func.png)、[命令及原始 TXT](../结果/JWT身份入口-20261121/最终-func/命令行输出.txt)。
- 对象版：[运行结果](../结果/JWT身份入口-20261121/最终-oo/结果.json)、[JaCoCo HTML](../结果/JWT身份入口-20261121/最终-oo/jacoco/index.html)、[结果截图](../结果/JWT身份入口-20261121/JaCoCo-oo.png)、[命令及原始 TXT](../结果/JWT身份入口-20261121/最终-oo/命令行输出.txt)。
- 交付核对：[文件范围、源码/构建摘要和跨版一致性](../结果/JWT身份入口-20261121/交付核对.json)、[静态契约检查](../结果/JWT身份入口-20261121/静态契约.txt)、[命令记录](../结果/JWT身份入口-20261121/命令记录.txt)。截图为真实最终报告页面，经目视复核；不是手绘结果。

首次交付静态扫描因先检查链接、后生成静态契约 TXT 而报告该证据链接缺失；该次核对 JSON 保留，先生成证据再复核。它不是 JWT 或原流程的运行失败，也不改变上述最终测试结果。

### 复现命令

在 C2 根目录执行；如需保留已有证据，应更换 --output-dir，不能覆盖本批次：

```powershell
python 测试/脚本/run_backend.py func --output-dir 测试/结果/JWT身份入口-20261121/最终-func --build-dir target/jwt-identity
python 测试/脚本/run_backend.py oo --output-dir 测试/结果/JWT身份入口-20261121/最终-oo --build-dir target/jwt-identity
```

运行器实际调用各版 Maven verify，构建及打包位于各自 target/jwt-identity，不执行 clean 或删除原报告。生产及既有测试改动清单、跨版一致性和正式 OpenAPI 未变的核对结果另归档到本证据目录。

## 尚未完成

- Redis 登录记录、原子刷新轮换、已消费摘要重用检测、退出撤销、Cookie/CSRF 与存储故障的 HTTP 映射尚未实现，属于第 3 部分。
- 正式安全链和 JWT 登录/刷新/退出响应尚未切换；第 3 部分装配时必须同时验证完整登录状态，不能先公开只验签路径。
- 前端仍按 Session 使用；JWT 页面恢复、并发/跨标签页协调、Cookie 参数和旧依赖退出属于第 4 部分。
- 本步不运行新的前端浏览器验收、不生成刷新或撤销已通过的结论；主 OpenAPI、Apifox、数据库迁移及部署入口未改。
- 后续完整 JWT 验收、图件更新和接口同步按第 5/6 部分进行；课程 Swarm 仍由队友部署/验收。

下一步实现真实 Redis 登录生命周期，再将本步组件装配成完整认证链；继续使用随机 sid，不引入 tokenVersion、用户版本字段或全账号计数器。
