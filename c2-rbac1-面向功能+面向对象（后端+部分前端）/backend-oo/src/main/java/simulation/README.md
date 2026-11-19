# 业务申请

业务端提供系统目录、分页列表、详情、发起、修改、撤回、审批、驳回和删除已驳回申请。契约见 [共同 OpenAPI](../../../../../api/openapi.yaml)，操作与测试见 [C2 运行说明](../../../../../README.md)。

## 授权与历史数据

列表和详情要求目标系统的 request:read，覆盖待审批、已审批、已撤回、已驳回及历史记录。已有申请的写操作同时要求查看权和对应动作权，修改、撤回及删除还检查申请人。发起只要求 request:create。组织停用优先，继承来源的重授按既有实时授权规则计算。

Flyway V8 为现存系统建立查看权限定义，只对约定的演示账号配置查看角色。历史申请保留空申请人，并继续支持有权限的审核。其他既有账号由管理员按角色授予查看权。

## 事务与日志

业务事务由 Service 控制，已有申请写入先锁定同一行，再授权和迁移状态。并发竞争只提交一次状态变化。Logback 成功写事件在事务提交后记录，拒绝和失败按实际结果记录。
## 对象版职责

BusinessRequestService 只依赖 simulation.port 中的 RequestRepository、RequestSystems、RequestAccess。分页结果通过端口的 Slice 返回。simulation.domain.BusinessRequest 负责 rename、withdraw、approve、reject、delete。领域层不依赖 JPA。

simulation.infrastructure.JpaRequestRepository 负责 JPA 行锁、实体转换和保存。RequestContextAdapter 对接业务系统仓储及既有 AuthorizationService。SimulatedRequest 是持久化映射，摘要、详情结果和公开 VO 使用 CloneFactory。RequestArchitectureTest 守住应用层端口依赖，BusinessRequestTest 验证纯领域规则。