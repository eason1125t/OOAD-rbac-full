# 业务系统与申请生命周期

## 核心职责

本模块提供公司、部门业务系统中的申请列表、详情、发起、修改、撤回、审批、驳回和逻辑删除。系统目录也被登录导航、授权、组织策略和初始化共用，不只供申请页面使用。两版对外契约一致，差别在状态规则与持久化职责的组织方式。

| 核心文件 | 作用 |
| --- | --- |
| [BusinessRequestController](BusinessRequestController.java) | 适配八类 HTTP 操作，接收可信员工身份，校验并映射响应 |
| [BusinessRequestService](BusinessRequestService.java) | 建立读取/写入事务，协调实时权限、申请状态与审计 |
| [BusinessRequestAudit](BusinessRequestAudit.java) | 集中记录申请结果并等待实际事务完成 |
| [SimulatedRequest](SimulatedRequest.java) / [SimulatedRequestRepository](SimulatedRequestRepository.java) | JPA 申请字段、未删除过滤、系统分页和悲观行锁 |
| [BusinessSystem](BusinessSystem.java) / [BusinessSystemRepository](BusinessSystemRepository.java) | 系统代码、组织归属、名称及默认工作区标识 |
| [RequestTitleDTO](dto/RequestTitleDTO.java)、[RequestDetailVO](vo/RequestDetailVO.java) 等 | 标题白名单校验与公开响应，API 不暴露 JPA Entity |

## API 与授权要求

| 操作 | 路径 | 关键权限/规则 |
| --- | --- | --- |
| 列表 | `GET /api/business-systems/{systemCode}/requests` | 系统准入及 `request:read`，返回未删除的所有状态 |
| 详情 | `GET /api/requests/{requestId}` | 从记录确定系统，再校验查看权 |
| 发起 | `POST /api/business-systems/{systemCode}/requests` | `request:create`，不额外要求查看权，申请人取会话 |
| 修改 | `PATCH /api/requests/{requestId}` | 查看权+修改权，本人且 PENDING |
| 撤回 | `POST /api/requests/{requestId}/withdraw` | 查看权+撤回权，本人且 PENDING |
| 审批 | `POST /api/requests/{requestId}/approve` | 查看权+审批权，PENDING；有权账号允许自审 |
| 驳回 | `POST /api/requests/{requestId}/reject` | 查看权+驳回权，PENDING；有权账号允许自审 |
| 删除 | `DELETE /api/requests/{requestId}` | 查看权+删除权，本人且 REJECTED，逻辑删除 |

创建为 201，删除为 204，修改/撤回/驳回返回 200 详情；审批保留 C1 的鉴权与执行结果结构。读取和写入响应均 `no-store`，准确字段见 [共同 OpenAPI](../../../../../api/openapi.yaml)。

## 核心函数流程

### `list/detail/create`

`list` 在只读事务内按系统代码解析系统、校验查看权，再计算 `allowedActions`。该字段只是本系统获准的写动作集合，不保证任意行都满足申请人及状态条件。查询统一过滤 `deletedAt IS NULL`，按 ID 升序分页，offset 超出 JPA 整数范围时返回空页及真实 total。

`detail` 先查未删除记录，再从其 systemId 判断查看权。`create` 只检查发起权限，用服务端员工 ID 建立 PENDING 记录；保存得到 ID 后补入审计目标。标题以 `@NotBlank/@Size(max=128)` 校验并去除首尾空白，`@JsonAnySetter` 拒绝客户端额外写入状态或申请人。

### 既有申请的 `change`

1. 在写事务内悲观锁定未删除申请行。
2. 由持久化记录确定系统，不接受客户端改写所属系统。
3. 分别验证 `request:read` 和目标动作权限。
4. 执行状态及所有权规则，返回服务结果，由 Controller 映射 VO。
5. 同一行的并发状态转换串行化；等待者读取新状态后冲突，不会重复成功提交。

对象版 Service 仅依赖 [RequestRepository](port/RequestRepository.java)、[RequestSystems](port/RequestSystems.java)、[RequestAccess](port/RequestAccess.java) 三个业务端口，仍使用 Spring `@Transactional` 管理用例事务。

[BusinessRequest](domain/BusinessRequest.java) 是无 JPA 的领域对象，封装 `create/rename/withdraw/approve/reject/delete`，状态常量归口自身。Service 用 `Consumer<BusinessRequest>` 选择领域行为，再显式调用端口 save。领域对象仍使用项目 `BusinessAccessException`，不能说完全没有其他包依赖。

[JpaRequestRepository](infrastructure/JpaRequestRepository.java) 将 JPA 实体还原为独立领域对象；保存已有 ID 时在同一事务中取得托管实体，`SimulatedRequest.apply` 仅复制标题、状态、删除时间，不改系统或申请人。[RequestContextAdapter](infrastructure/RequestContextAdapter.java) 实现系统查询与授权端口，接入既有系统仓储及 AuthorizationService；[RequestSystem](domain/RequestSystem.java) 是应用可见的系统标识 record，仅保留实际使用的 ID，移除未消费的 code 字段。类型和三个端口保留，用于隔离应用层与 JPA；没有增加跨请求系统或权限缓存。

## 状态规则及兼容数据

- 修改只更新本人 PENDING 标题，状态不变；撤回变为 WITHDRAWN，原单保留且不能重新提交。
- 审批、驳回分别将 PENDING 变为 APPROVED、REJECTED。重复审批已通过单保留 `REQUEST_ALREADY_APPROVED` 专用错误，其余非法迁移为 `REQUEST_STATE_CONFLICT`。
- 删除仅设置 UTC `deletedAt`，保留 REJECTED 状态。列表、详情和写入锁查询均排除已删除记录。
- C1 历史记录允许 applicantId 为空，可以在获准时查看/审核，不能执行本人专属动作。操作者通过结果日志追踪，当前记录没有额外的“驳回人”字段。
- Flyway V8 为系统补定义查看权；普通既有账号仍由管理员授权，不将“存在权限定义”当作自动获权。

## 事务结果日志与特别技术

`BusinessRequestAudit.record` 包装每次业务操作，并注册 `TransactionSynchronization.afterCompletion`。读操作在提交后记 SUCCEEDED，写操作提交后才记 COMMITTED；回滚记 FAILED。业务拒绝记 REJECTED 并重抛，其他运行异常记 FAILED 并重抛。方法返回不等于事务已经提交，因此不能提前记录写成功。

日志为 `rbac1.oo.request`，只含版本、动作、actorId、systemId、requestId、结果和错误码，不含标题、密码、Cookie 或 CSRF。该类是集中申请审计入口，与 contract 的普通分层 AOP 日志作用不同。

技术包括 Spring MVC/Bean Validation、JPA 悲观锁、事务同步回调、UTC 逻辑删除和 CloneFactory。`RequestSummary/RequestDetail` 使用 `@CopyFrom`；公开 VO 的 `from` 再用生成的 `CloneFactory.copy`，编译时生成同名同类型字段映射，不用运行时反射。ID/系统/申请人等身份字段不通过客户端批量复制。

## 调用、反向依赖和验证

`业务页面 → BusinessRequestController → BusinessRequestService → 实时授权/申请存储 → Audit 回调`。功能版直接接仓储；对象版通过业务端口接基础设施，具体见上文。

`WorkspaceService`、`AuthorizationService`、分配和组织策略服务使用系统目录；初始化复用申请仓储、动作常量和样例，不能把系统目录移动成只属于某个 Controller 的私有数据。

现有 `AuthSessionTest` 验证 HTTP 授权、本人边界、状态竞争、历史数据和提交日志。对象版另有 `BusinessRequestTest` 纯领域测试和 `RequestArchitectureTest` 检查应用服务只依赖三端口。 现有浏览器用例包括 request-read、角色权限及双版本隔离。历史结果见 [测试记录](../../../../../测试/历史/双版本测试记录.md)，第 2 项测试重组与 JaCoCo 归档已完成，最新证据见测试目录的执行报告。
