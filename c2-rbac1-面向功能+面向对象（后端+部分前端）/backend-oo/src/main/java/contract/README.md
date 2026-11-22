# 接口契约、统一错误与分层日志

## 模块职责与边界

本模块为所有 HTTP 用例提供稳定错误结构、业务异常类型和方法日志；申请事务结果日志由 simulation 的专门审计入口负责。契约的主文件是 C2 [openapi.yaml](../../../../../api/openapi.yaml)。两版本包实现相同，共用业务错误语义。

| 文件 / 函数 | 实现重点 |
| --- | --- |
| [BusinessAccessException](BusinessAccessException.java) / `Reason` | 将拒绝原因定义为稳定业务码及中文默认消息，允许同码补充场景消息 |
| [ApiExceptionHandler.businessFailure](ApiExceptionHandler.java) | 将业务原因映射到 400、401、403、404、409 |
| `invalidInput` | 将 Bean Validation、类型转换、方法约束、非法 JSON、缺参转换为 400，不回传底层原始错误文本 |
| `unavailable` | 从数据访问或事务创建异常中识别 Redis 会话原因，否则归类数据库授权不可用 |
| `error` | 统一构造 `ApiErrorVO(code,message)` 及 `Cache-Control: no-store` |
| [LayerLoggingAspect.trace](LayerLoggingAspect.java) | 环绕指定入口的公开方法，记录开始、耗时和异常类型并保留原调用结果/异常 |
| [ApiErrorVO](vo/ApiErrorVO.java) | 所有 HTTP 错误的公开结构 |

## 错误如何跨模块传播

`Controller → Service / 领域规则 → BusinessAccessException → MVC Advice → ApiErrorVO`。Advice 对目录参数组合/类型的 VALIDATION_FAILED 返回400，对 AUTHENTICATION_REQUIRED 返回 401，系统/操作越权返回 403，不存在返回 404，申请状态、分配、资源及继承冲突返回 409。

请求格式错误由 Controller/DTO 校验，业务状态和授权错误由 Service/领域规则产生。唯一键等数据库错误仍由触发写入的用例按已有边界转换，不把全部表名判断堆入 Advice。

登录凭据错误在 `AuthController` 的局部处理器转换，并复用本模块 `error`。安全过滤器发生在 MVC 之外，由 `SecurityConfiguration` 直接写同一 `ApiErrorVO` 结构；不能声称所有安全错误都经过 Advice。

`unavailable` 使用 `AuthenticationStoreFailure.matches` 沿原因链区分 `503 AUTHENTICATION_UNAVAILABLE` 和 `503 AUTHORIZATION_UNAVAILABLE`，不把所有连接超时统一说成 Redis 故障。无法授权时不会继续执行申请写入。

## AOP 日志的真实覆盖范围

`trace` 的切点覆盖 `@RestController/@Service` 的公开方法（含独立的 `AccountAuthentication.verify`），以及 `LoginAuthentication.start/refresh/end` 和 initialization/authentication 中 `ApplicationRunner.run`。它没有普遍匹配所有 `@Repository` 或 `simulation.infrastructure` 的适配器；通过这些类调用的 Service 仍会形成对应入口日志。JWT认证已装配，日志不得输出访问凭证、刷新凭证或密钥。

`loggerName` 按职责输出 `rbac0.api`、`rbac0.service`、`rbac0.infrastructure` 加实际类名。前缀是当前沿用的配置，不能在说明中虚写为已改成 rbac1。`System.nanoTime` 用于单调计时，DEBUG 仅记录方法名、耗时、异常类型，不输出参数、返回体或异常堆栈。

[application.yaml](../../resources/application.yaml) 用 `RBAC_LOG_API/SERVICE/INFRASTRUCTURE` 配置级别，根 INFO、业务 DEBUG。当前使用 Spring Boot 默认 Logback，没有自定义 `logback-spring.xml`。申请业务结果另由 [BusinessRequestAudit](../simulation/BusinessRequestAudit.java) 在事务完成后写 `rbac1.oo.request`，集中审计应保留，不能当作普通类散落日志删除。

## CloneFactory 与构建链

Controller 将服务结果转为公开 VO。可变对象的同名同类型字段优先 `@CopyFrom` 与 `CloneFactory.copy`；派生/敏感字段及不可变 record 当前使用明确构造。DTO 校验不由复制工具代替；对象版领域与持久化边界不以无差别字段复制绕过。

Lombok 和课程 CloneFactory 同列于 Maven `annotationProcessorPaths`。生成源码在 `target/generated-sources/annotations/cn/edu/xmu/clonefactory/util/CloneFactory.java`，打包排除处理器本身。首次构建准备及版本来源见 [CloneFactory 说明](../../../../tools/clonefactory/README.md)。

## 测试、接口平台与后续工作

现有 [AuthHttpTest](../../../../../测试/后端/common/java/contract/AuthHttpTest.java) 验证会话、权限、状态、并发与日志。Surefire 挂 JaCoCo agent 生成 `target/jacoco.exec`，`mvn verify` 才执行 report 更新 HTML/XML；`mvn test` 不等于报告已刷新。当前没有配置覆盖率门槛 `check`，不臆造达标百分比。

本地JWT契约为OpenAPI2.5.0，49操作/44模型；此前已完成的Apifox同步为2.4.0，不能宣称JWT已远端同步。按已授权的第6部分，在最终验证后更新Apifox并归档实际回读证据。

运行条件和历史结果见 [阶段 README](../../../../../README.md)、[双版本测试记录](../../../../../测试/README.md)。已在 `测试/` 完成测试整理及真实回归，归档测试代码、说明、JaCoCo 图及原始报告、命令和输出 TXT；Swarm 日志复验另随部署交付。
