# 接口契约与验收

状态：持续维护。参见 [C1 后端开发规划](../../../../../后端开发规划.md)。

## 应有功能

负责 OpenAPI、统一错误、分层日志、集成测试和演示验收。

## 实现方式

以 C1 的 `api/openapi.yaml` 记录路径、权限、会话与错误。使用 Logback 和统一分层日志，通过 RBAC_LOG_API、RBAC_LOG_SERVICE、RBAC_LOG_INFRASTRUCTURE 分别设置级别。按需求编号测试成功、失败、事务回滚和同会话实时生效。集成测试使用独立 MySQL / Redis，JaCoCo 用于检查实际执行路径。

## 代码入口

[ApiExceptionHandler](ApiExceptionHandler.java) 映射业务错误，[LayerLoggingAspect](LayerLoggingAspect.java) 统一记录日志，[AuthSessionTest](../../../test/java/contract/AuthSessionTest.java) 覆盖 HTTP 会话、授权和审批。

## 与其他模块的关键联系

接入所有模块的验收样例。Apifox 以同一契约联调。C2、C3 两种后端复用数据、请求和预期结果。

## 验收

仓库的 [OpenAPI](../../../../../api/openapi.yaml) 收录当前 35 个操作。Apifox 团队项目「ooad项目 / OOAD RBAC」的 `C1-RBAC0` 模块曾导入首批 11 个操作。本次更新以仓库为准。按 [部署运行说明](../../../../../部署运行说明/README.md) 配置本地环境后执行 [接口验收](../../../../../api/首批接口验收.md)。

Maven 集成测试覆盖会话适配器的凭据校验、建立和结束会话日志，验证日志只记录操作及异常类型。MySQL、Redis 断连与本地双端联调已通过复验，课程环境待复验。
