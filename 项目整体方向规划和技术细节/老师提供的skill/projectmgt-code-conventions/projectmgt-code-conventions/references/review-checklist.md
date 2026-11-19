# 代码审查清单

## API

- [ ] Controller 使用 `@Valid`。
- [ ] DTO 校验字段格式、范围、枚举和跨字段关系。
- [ ] Service 没有重复做请求格式校验。
- [ ] 输入是 DTO，输出是 VO。
- [ ] DTO → Command、Result → VO 映射位置正确。
- [ ] 错误码和中文消息符合 OpenAPI。
- [ ] 按 id 的接口覆盖不存在 id。

## Java

- [ ] Java 中无 `var`。
- [ ] 类的职责和非显然方法有中文 Javadoc。
- [ ] Lombok 已消除不必要样板代码。
- [ ] 同名字段不需要手写 set 链。
- [ ] CloneFactory 注解和生成方法匹配。
- [ ] 类型不同字段已显式排除并转换。
- [ ] 修改 CloneFactory 注解后执行过 clean build。

## Service

- [ ] 事务边界在 Service。
- [ ] 权限和业务规则在 Service。
- [ ] Controller 不写业务逻辑。
- [ ] 已完成的业务对象限制规则有测试。
- [ ] 没有不必要的 `flush()`。

## 日志

- [ ] API、Service、Infrastructure 日志统一由 `LayerLoggingAspect` 输出。
- [ ] 业务类没有散落 `@Slf4j`、Logger 或 `log.*` 调用。
- [ ] `application.yaml` 将 API、Service、Infrastructure 包设为 DEBUG，根日志为 INFO。
- [ ] AOP 日志不打印参数、DTO/VO、令牌、Authorization 头或敏感数据。
- [ ] Docker Compose/Swarm 使用 `LOGGING_LEVEL_CN_EDU_XMU_PROJECTMGT_*` 环境变量覆盖日志级别。\n- [ ] `logback-spring.xml` 只负责 appender 和格式，不写死业务包级别。\n- [ ] 日志只记录层名、类名、方法名、耗时和异常类型。
## 外部集成

- [ ] 外部 HTTP 接口使用 `@HttpExchange` 声明，而不是在 Service/Controller 中直接拼装请求。
- [ ] `@HttpExchange` 客户端位于 Infrastructure，Service 只依赖业务端口。
- [ ] 外部请求/响应使用专用 DTO/record，没有泄漏 Entity、API VO 或领域对象。
- [ ] Bearer Token、基础 URL 和超时来自配置或请求上下文，没有硬编码或写入日志。
- [ ] 外部 4xx/5xx、超时和连接失败已转换为项目业务错误；权限查询失败时 fail closed。
- [ ] 外部集成测试使用端口契约测试或 HTTP stub，并验证真实业务结果。
## 持久化

- [ ] 新约束有数据库层实现。
- [ ] Flyway 只新增版本。
- [ ] 唯一索引/检查约束错误已转换为业务错误。
- [ ] 公共审计字段完整。

## 测试

- [ ] 先见证 RED，再实现。
- [ ] 测试名包含需求编号。
- [ ] URL 常量集中在类顶部。
- [ ] 业务错误码有正向或负向契约测试。
- [ ] 数据库使用真实 MySQL。
- [ ] 完整回归通过。