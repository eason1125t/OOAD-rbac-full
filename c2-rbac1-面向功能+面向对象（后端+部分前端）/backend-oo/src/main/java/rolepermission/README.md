# 角色、权限与 RBAC1 继承

## 核心职责和数据

本模块保存角色、运行时权限及原表功能清单，维护同归属角色的直接继承图。角色定义的 CRUD 和读取入口位于 organization 的管理 Controller/Service；继承写入入口位于本包。这里的角色继承是数据关系，不是 Java `extends`。

| 文件 | 职责 |
| --- | --- |
| [RbacRole](RbacRole.java) / [Permission](Permission.java) | 角色归属和系统内动作定义；权限身份由系统 ID 与 code 区分 |
| [CatalogEntry](CatalogEntry.java) | 课程功能权限清单的原始动作文本，仅用于核对，导入不自动授予运行时权限 |
| [RoleInheritance](RoleInheritance.java) / [RoleInheritanceKey](RoleInheritanceKey.java) | `senior_role_id`、`junior_role_id` 的组合主键映射，两个外键均引用角色 |
| [RoleInheritanceController](RoleInheritanceController.java) | 继承关系 PUT/DELETE 资源入口，成功返回 204 |
| [RoleInheritanceService](RoleInheritanceService.java) | 同事务编排图写锁、范围、成环及新增授权影响检查 |
| [RoleInheritanceRepository](RoleInheritanceRepository.java) | JPA 继承边读写及单行图写锁 |
| [RbacRoleRepository](RbacRoleRepository.java) / [PermissionRepository](PermissionRepository.java) | 管理目录、直接关系、继承配置权限与实时授权所需查询 |

## 继承方向与业务边界

`senior → junior` 表示高级角色接收低级角色提供的权限。API 中 `roleId` 是接收者，`parentRoleId` 是来源；页面显示“继承自”。公司/部门层级不自动产生角色继承，也没有全局角色等级字段。

两端须同组织归属；共享角色两端均为空归属，仅全局管理员维护。自继承和成环为 409；已有同方向传递路径上再增加直达边合法。重复合法 PUT/DELETE 幂等。删除角色前必须解除员工分配、直接权限和任一方向继承引用。

## `RoleInheritanceService.change` 的实现顺序

1. `assignmentScope(actor, ROLE_PERMISSION_WRITE)` 取得管理员资格。此时尚未完成具体两端组织范围判定。
2. `hierarchy.lockWrites()` 锁住 `role_hierarchy_lock` 的唯一记录，再读取两个端点角色；自继承时复用同一角色。此服务不额外取得角色悲观行锁。
3. `AssignmentService.requireManaged` 检查两个端点均可管理。
4. DELETE 直接移除边并返回；新增才检查同归属、自继承和已有边。
5. 读取当前图快照，`wouldFormCycle` 检查从 junior 是否已经可达 senior。
6. `requireInheritanceGrant` 检查新增来源对 senior 及所有依赖方的既有成员、归属和停用策略的影响。
7. 校验完成后保存边。READ_COMMITTED 事务将图锁保持到事务结束；并发写入等待后再读新图，防止两条分别合法但合并成环的边同时提交。

## 图计算原理

`inheritedRoles(roots)` 用队列遍历邻接表，访问集合保证同一角色只展开一次，结果包含 roots。单次遍历时间为 `O(V+E)`。`dependingRoles(roleId)` 先建立反向图再展开，找所有依赖该角色的高级角色，供后加权限时检查影响。

`wouldFormCycle(senior,junior)` 从 junior 出发检查 senior；方向颠倒会误拒绝正常继承。访问集合也使异常含环输入能够终止，但数据库写入仍必须在加边入口拒绝环路。

对象版 [RoleHierarchy](domain/RoleHierarchy.java) 在构造时复制 Map 和每个内部 Set 为不可变快照，自己承担闭包与查环。[RoleInheritanceStore](RoleInheritanceStore.java) 是业务端口，[JpaRoleInheritanceStore](JpaRoleInheritanceStore.java) 实现该端口并装载领域对象；应用用例不直接依赖继承 JPA Repository。

## 查询、调用和被调用

- `RoleInheritanceController → RoleInheritanceService → AuthorizationService + AssignmentService + RoleInheritanceStore + RbacRoleRepository`。
- [ManagementQueryService.roleParents](../organization/ManagementQueryService.java) 通过 `findParents` 只返回一跳直接来源。
- 同服务 `inheritedRolePermissions` 展开来源、移除根角色、按权限 ID 去重并分页。这是角色配置清单，不能当作员工受组织策略约束后的实际权限。
- [AuthorizationService](../authorization/AuthorizationService.java) 使用快照和员工直接角色计算有效权限；[AssignmentService](../assignment/AssignmentService.java) 使用正反向闭包校验新增授权。
- [ManagementMaintenanceService.deleteRole](../organization/ManagementMaintenanceService.java) 使用图锁及 `references` 保护删除；初始化也经同一存储写入演示图。

## 特别技术、错误和验证

`@IdClass` 及组合主键防重复，Flyway V6 设置外键、逆向索引、自继承检查和图锁表。`lockGraph` 是 Spring Data JPA Repository 的原生 `SELECT ... FOR UPDATE`，没有绕过 Repository 直接使用 JDBC。锁记录缺失为数据读取故障，不应继续写边。

范围拒绝为 403；缺失角色为 404；自继承、环路、不同归属及分配策略冲突按共同契约返回 409。图规则单测及 HTTP 并发/原会话撤权测试见 [历史测试记录](../../../../../测试/历史/双版本测试记录.md)，图源和代码追踪见 [设计索引](../../../../../docs/design/README.md)。本模块不引入 C3 互斥或角色激活规则。
