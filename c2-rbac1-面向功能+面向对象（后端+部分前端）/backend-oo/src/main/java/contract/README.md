# 接口契约与验收

持续维护 C2 的共同契约，实施与结果见 [双版本测试记录](../../../../../双版本测试记录.md)。

## 应有功能

负责 OpenAPI、统一错误、分层日志、集成测试和演示验收。

## 实现方式

以 C2 的 `api/openapi.yaml` 记录路径、权限、会话与错误。使用 Logback 和统一分层日志，通过 RBAC_LOG_API、RBAC_LOG_SERVICE、RBAC_LOG_INFRASTRUCTURE 分别设置级别。按需求编号测试成功、失败、事务回滚和同会话实时生效。集成测试使用独立 MySQL / Redis，JaCoCo 用于检查实际执行路径。

## 代码入口

[ApiExceptionHandler](ApiExceptionHandler.java) 映射业务错误，[LayerLoggingAspect](LayerLoggingAspect.java) 统一记录日志，[AuthSessionTest](../../../test/java/contract/AuthSessionTest.java) 覆盖 HTTP 会话、授权和审批。

## 与其他模块的关键联系

接入所有模块的验收样例。Apifox 以同一契约联调。C2、C3 两种后端复用数据、请求和预期结果。

## 验收

仓库的 [共同 OpenAPI](../../../../../api/openapi.yaml) 收录 46 个操作。Apifox 的 `OOAD RBAC / C2-RBAC1` 模块已导入 46 个操作和 29 个模型，并回读继承权限路径、中文说明、分页结构及双版 Cookie。2026-11-19 再次同步了 7 项申请接口的查看权限说明，并回读列表的 request:read、403 和写动作约定。运行方式见 [C2 README](../../../../../README.md)，场景见 [申请验收矩阵](../../../../../api/申请CRUD验收矩阵.md)。

Maven 集成测试覆盖会话适配器的凭据校验、建立和结束会话日志，验证日志只记录操作及异常类型。MySQL、Redis 断连与本地双端联调已通过复验，课程环境待复验。以上为既有执行记录，本次状态同步未重跑。后续在 C2 的 `测试/` 重新制作测试代码和说明，统一归档两版 JaCoCo 结果图、实际命令及命令行输出 TXT；完成后再更新本页入口。见 [收尾清单](../../../../../收尾清单.md)。
