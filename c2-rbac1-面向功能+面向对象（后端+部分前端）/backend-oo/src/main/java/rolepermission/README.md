# 角色与权限

C2 阶段进度以 [开发方案](../../../../../开发方向细节.md) 和 [收尾清单](../../../../../收尾清单.md) 为准，以下 C1 文档作为沿用功能的基线参考。

状态：角色和权限定义维护、关联查询、原表清单查询及 RBAC1 继承维护已实现。参见 [C1 基线开发规划](<../../../../../../c1-框架-rbac0-结构化+面向功能/后端开发规划.md>)。

## 应有功能

负责角色和运行时权限定义的 CRUD、同归属角色直接继承边的维护，以及 Excel 功能权限清单的只读查询。

## 实现方式

业务权限按系统与权限码标识。全局共享定义由超级管理员维护，局部定义按归属校验。编辑只改变名称。角色仍被分配、权限仍被分配或有组织策略引用时，删除返回 `409 RESOURCE_CONFLICT`。清单原动作文本和空值用于展示。角色有继承引用时须先解除继承边再删除。

[RoleInheritanceService](RoleInheritanceService.java) 编排事务、图锁、管理范围和新增授权检查，通过 [RoleInheritanceStore](RoleInheritanceStore.java) 加载与保存继承关系。[RoleHierarchy](domain/RoleHierarchy.java) 持有关系快照并计算闭包与环路；[JpaRoleInheritanceStore](JpaRoleInheritanceStore.java) 适配 JPA 持久化。拒绝自继承、成环和跨归属连边，共享图由全局管理员维护。

## 与其他模块的关键联系

向授权配置提供可选角色与权限定义，实时授权读取分配、定义和继承闭包。直接与传递路径按权限 ID 去重，撤销唯一有效路径后原会话下一次请求拒绝。C3 约束配置尚未实施。

## 代码与接口

本包维护 [RbacRole](RbacRole.java)、[Permission](Permission.java)、[CatalogEntry](CatalogEntry.java) 及其 Repository。用例编排由 [ManagementQueryService](../organization/ManagementQueryService.java) 与 [ManagementMaintenanceService](../organization/ManagementMaintenanceService.java) 负责。

角色和权限分别使用 `/api/roles` 和 `/api/permissions`。Excel 功能清单通过 `/api/catalog-entries` 只读分页，原表动作和运行时授权分别维护。

`GET /api/roles/{roleId}/parents` 查询一跳直接继承，`PUT`／`DELETE /api/roles/{roleId}/parents/{parentRoleId}` 维护继承边。员工有效权限通过 `GET /api/employees/{employeeId}/effective-permissions?systemCode=...` 查询，统一接受管理范围和实时授权规则校验。

## 验收

已测试同码隔离、跨组织修改拒绝、重复创建、先解绑再删除，以及原表空值保留。继承规则、并发成环、实时撤权和组织重授的既有结果见 [双版本测试记录](../../../../../双版本测试记录.md)；收尾后的测试重做与回归尚未执行。
