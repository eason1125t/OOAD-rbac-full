---
name: projectmgt-code-conventions
description: 在「课程项目管理系统」中编写或修改生产代码时使用：约束 API 层参数校验、DTO/VO、CloneFactory、Lombok、中文注释、分页、数据库约束、错误翻译和 TDD。不用于一次性原型探索、纯文档修改或 UML 绘图。
metadata:
  short-description: 课程项目管理系统生产代码规范
---

# 课程项目管理系统代码规范

## 何时使用

用于本项目后端、生产前端、测试、数据库迁移和接口实现的代码改动。`prototype/` 下的一次性界面探索不执行这些生产代码规则。

## 必须遵守

1. **测试先行**：先写能因目标行为缺失而失败的测试，确认 RED，再写最小实现，最后完整回归。
2. **API 层完成输入校验**：字段非空、长度、范围、枚举、起止时间先后、人数上下限等请求合法性放在 DTO/Controller。使用 Bean Validation、`@AssertTrue` 或自定义校验器；Service 不承担请求格式校验。
3. **Service 处理业务规则**：权限、状态机、完成锁定、跨对象一致性等放在 Service；错误使用项目业务异常。
4. **DTO 与 VO 分离**：传入请求使用 `dto` 包并以 `DTO` 结尾；返回对象使用 `vo` 包并以 `VO` 结尾。Controller 负责 DTO → Command、Result → VO 映射。
5. **优先 CloneFactory**：同名字段、同类型字段优先 `CloneFactory.copy(...)`；PATCH 更新优先 `copyNotNull(...)`；`toResult(...)` 也优先 CloneFactory。派生值、多来源字段、类型转换或业务计算字段才显式 set。
6. **Lombok 去样板代码**：优先 `@Getter`、`@Setter`、`@RequiredArgsConstructor`、`@NoArgsConstructor`；不要手写无意义 getter/setter/constructor。
7. **禁止 `var`**：Java 局部变量必须写明确类型。
8. **中文注释**：每个类、接口、记录和公开/关键私有方法写中文 Javadoc，说明职责和非显然规则。
9. **列表分页**：会增长的列表统一 `page`、`pageSize`、`total`；仅固定且很少的数据可一次返回全部。
10. **数据库兜底约束**：业务不变量用数据库唯一索引、检查约束、外键兜底；Flyway 只新增迁移，不修改已执行迁移。
11. **错误就地翻译**：唯一索引、检查约束等数据库错误在触发保存或删除的 Adapter/Service 处转换为稳定业务错误码，不集中到 Controller Advice 后做表名 if/else。
12. **不要手工 flush**：普通保存使用 `save`/`saveAll`，事务结束时提交。仅当必须在当前事务边界捕获唯一约束时才使用 `saveAndFlush`/`saveAllAndFlush`，不要额外调用 `flush()`。
13. **事务边界在 Service**：跨仓储写操作必须在同一业务事务内完成。
14. **统一身份认证**：不保存本地密码；教师/学生只保存统一身份 `user_id` 映射。未关联学生的 `user_id` 可为空。
15. **Spring Security 限制在 infrastructure**：业务权限判断放 Service 权限守卫，不散落 Controller 或领域对象。
16. **外部系统调用使用 `@HttpExchange`**：统一身份认证、审计、语音转写等外部 HTTP 服务必须通过 Spring HTTP Interface `@HttpExchange` 声明客户端，不由 Service 或 Controller 直接调用 `RestClient`、`WebClient` 或拼装请求。
17. **统一 AOP 日志**：API、Service、Infrastructure 的日志统一由 `LayerLoggingAspect` 输出；业务类不得散落 `@Slf4j` 或 `log.*` 调用。日志级别写在 `application.yaml` 的 `logging.level`，并在 Docker Compose/Swarm 部署文件中通过 `LOGGING_LEVEL_CN_EDU_XMU_PROJECTMGT_*` 环境变量覆盖；`logback-spring.xml` 只负责 appender 和格式。
18. **DDD 应用服务只依赖领域仓储端口**：service/ddd/application/** 只能依赖 service/ddd/domain/repository、service/ddd/domain/model 和 service/port/** 中的抽象。禁止引用 JpaRepository、JPA Entity、Spring Data Repository 或 infrastructure.persistence；持久化实现必须放在 infrastructure 适配器中，DDD 应用服务不得了解数据库技术。
19. **DDD 状态和属性值常量归口领域对象**：课程状态、申请状态、预约状态、检查状态等 Status 常量必须定义在对应领域对象中，并使用 public static final。运行方式、检查方式、主体类型、审批结论、批改结论等属性值常量也必须归口对应领域对象，例如 Course.WORK_MODE_GROUP、Milestone.MODE_ORAL、OralBooking.SUBJECT_TYPE_GROUP、OralAssessment.VERDICT_PASSED。DDD 的 application/domain 不得重复定义私有状态字符串、魔法数字，也不得依赖 service.common 中的状态常量类。

## 日志规范

- 唯一的业务日志入口是 `infrastructure.logging.LayerLoggingAspect`。AOP 切面覆盖 `cn.edu.xmu.projectmgt.api`、`.service`、`.infrastructure`，记录方法开始、完成、耗时和异常类型。
- API、Service、Infrastructure 业务类中不得再添加 `@Slf4j`、手写 Logger 或零散 `log.debug/info/error`；需要日志时扩展统一切面，而不是侵入业务代码。
- 日志级别写在 `application.yaml` 的 `logging.level`，三个业务包为 `DEBUG`、根日志为 `INFO`。Docker Compose/Swarm 必须通过 `LOGGING_LEVEL_CN_EDU_XMU_PROJECTMGT_API`、`..._SERVICE`、`..._INFRASTRUCTURE` 环境变量显式覆盖。
- AOP 日志不打印参数值、DTO/VO、异常堆栈或完整响应体，只打印层名、类名、方法名、耗时和异常类型。
- 严禁记录密码、JWT、Bearer Token、Authorization 头、数据库密码、对象存储密钥、完整学生隐私数据或口试录音内容。
- 外部 HTTP 调用只记录目标系统、operationId 或路径、结果状态和耗时，不记录 Authorization 头或完整响应体。
## 外部系统调用

- 外部 HTTP 客户端接口放在 `infrastructure.<system>.client`，使用 `@HttpExchange` 声明基础路径，方法使用 `@GetExchange`、`@PostExchange`、`@PutExchange`、`@DeleteExchange` 等注解。
- 使用 `HttpServiceProxyFactory` 为接口创建代理；底层可采用 `RestClient` 或 `WebClient`。基础 URL、超时和认证配置必须来自 `application.yaml` 或部署环境，不得硬编码。
- Service 只依赖 `service.port` 中的业务端口的接口；由 Infrastructure 的适配器实现该端口并委托给 `@HttpExchange` 客户端。Controller 和 Service 不得直接创建 HTTP 客户端。
- 外部请求/响应模型放在 Infrastructure 客户端包中，使用专用 DTO 或 record；不得复用 Entity、API DTO/VO 或领域对象作为外部契约。
- 需要携带 Bearer Token 时，通过 `@RequestHeader("Authorization")` 方法参数或统一拦截器传入；不得把令牌写入日志、异常消息或持久化存储。
- 外部系统的 4xx、5xx、超时和连接失败必须在适配器处转换为项目业务错误；权限相关调用失败必须 fail closed，不得放行。
- 测试外部集成时，使用真实端口契约测试或本地 HTTP stub。不要断言 HTTP 客户端内部调用次数来替代业务行为验证。
- 当前系统与统一身份认证的边界：`/auth/*` 由统一身份认证系统实现，课程管理系统不实现这些 Controller 或代理接口；本系统 `GET /me` 通过端口调用统一身份认证 `GET /auth/me` 获取角色和前端权限。
## CloneFactory 细节

- 目标类可用 `@CopyFrom`、`@CopyNotNullFrom`；来源类可用 `@CopyTo`。
- 同名字段但类型不同（如 `OffsetDateTime` 与 `Instant`）使用字段级 `@CopyFrom.Exclude` 排除，再显式转换。
- 创建对象时先复制共同字段，再补充 `courseId`、聚合 id、派生标记等。
- 更新对象时先 `copyNotNull`，再处理类型转换和业务派生字段。
- 修改 CloneFactory 注解后执行 `mvn clean test`，避免旧生成源码残留。

## API 约定

- 所有业务 id 使用整数 `Long`/`Integer`，对外 OpenAPI 声明为 `integer`。
- 请求体使用 `@Valid`；Controller 不手写 request map。
- 跨字段校验放在 DTO：例如 `minSize <= maxSize`、`startAt < deadline`。
- 业务错误响应包含稳定 `code` 和可直接展示的中文 `message`。
- Controller 只做 HTTP 适配、校验、DTO/Command 映射，不写业务查询和持久化逻辑。
- 未找到、冲突和无权限分别返回正确的 404/409/403。

## 测试约定

- 测试名和 `@DisplayName` 带需求编号，如 `R7`、`R6b`。
- URL 模板集中为测试类顶部的 `private static final String`。
- 每个业务错误码至少有一个契约测试；所有按 id 操作的接口覆盖不存在 id。
- 数据库集成测试使用真实 MySQL，不 mock 数据库；测试事务回滚。
- 优先共享契约测试，避免同一行为在多个测试类复制断言。
- TDD 的 RED 必须是行为失败，不能靠语法错误或测试写错制造失败。

## 同步 OpenAPI 到 Apifox

本项目已有固定同步脚本，禁止临时臆造 Apifox API：

1. 修改 `api/openapi.yaml`。
2. 从 YAML 重新生成 `api/openapi.json`。
3. 确认目标项目 id；当前为 `8913225`。
4. 在 `prototype` 目录执行：

```powershell
$env:APIFOX_ACCESS_TOKEN='<从 ~/.codex/config.toml 的 project-api 配置读取>'
node scripts/push-apifox.mjs --project=8913225
```

5. 检查 `endpointFailed=0`、`schemaFailed=0`。
6. 通过官方只读 MCP 执行 `refresh_project_oas`，确认远端包含新路径和模型。

硬性约束：

- 必须使用 Node `fetch` 的 `prototype/scripts/push-apifox.mjs`。
- 禁止使用 PowerShell `Invoke-WebRequest -Body <string>` 推送，中文 tag 可能乱码。
- MCP `project-api` 只有读取/刷新工具，不负责写入。
- 推送使用 `OVERWRITE_EXISTING` 和 `deleteUnmatchedResources=true`；执行前确认当前 spec 完整。
- 只在用户明确要求同步 Apifox 时执行写操作。
- 推送后必须回读验证，不能只看 HTTP 200。
- 详细步骤见 `references/apifox-sync.md`。
## 完成后自检

- [ ] 输入格式和跨字段校验在 API 层。
- [ ] Service 只保留业务权限、状态和领域规则。
- [ ] DTO/VO 未泄漏 Entity。
- [ ] DDD 状态和属性值常量定义在对应领域对象中且为 public static final。
- [ ] 同类型同名字段优先使用 CloneFactory。
- [ ] 没有 `var` 和手写无意义样板代码。
- [ ] 新增类和关键方法有中文 Javadoc。
- [ ] 分页、错误码、权限和不存在 id 场景有测试。
- [ ] 数据库约束和迁移已覆盖。
- [ ] 没有不必要的 `flush()`。
- [ ] 完整测试通过。

需要逐项审查时读取 `references/review-checklist.md`。
## 代码生成格式硬约束

- **中文注释**：所有生产代码和测试代码中的类、接口、record、枚举以及公开方法和关键私有方法，必须写中文 Javadoc；注释应说明职责或非显然规则，不能只写“获取字段”等无信息内容。
- **真实换行**：生成或写入源码时必须使用实际换行符，禁止在 Java 文件中写入字面量 `\n`、`\r\n` 或 `\\n` 作为代码换行。
- **禁止单行压缩类**：类声明、注解、字段、构造器和方法必须按 Java 常规格式分行书写；不得为了缩短输出把多个注解、字段或方法压到同一行。
- **写入后自检**：批量生成或脚本写入后必须检查源码中是否出现字面量 `\n`，并运行 `mvn test-compile`；发现格式或编码错误时先修复，不得继续下一步。
- **CloneFactory 映射**：`toVO`、`toPageVO`、`toApplicantVO`、`toResult` 等映射方法在字段同名同类型时优先使用 CloneFactory；集合元素类型变化和派生字段才保留显式赋值。