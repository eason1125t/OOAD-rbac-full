# 角色与权限

面向功能版在 C1 的角色、权限定义和原表查询上增加了 RBAC1 继承维护。参见 [C2 验收记录](../../../../../README.md)。

## 应有功能

负责角色和运行时权限定义的 CRUD、角色直接继承关系的维护，以及 Excel 功能权限清单的只读查询。

## 实现方式

业务权限按系统与权限码标识。全局共享定义由超级管理员维护，局部定义按归属校验。编辑只改变名称。角色仍被分配、权限仍被分配或有组织策略引用时，删除返回 `409 RESOURCE_CONFLICT`。清单原动作文本和空值用于展示。

高级角色从同归属的低级角色获取权限，共享角色之间的继承由全局管理员维护。[RoleInheritanceService](RoleInheritanceService.java) 依次检查操作范围、取得写锁、校验归属和图，再检查新增授权是否符合组织策略。角色有继承引用时须先解除继承边再删除。自继承和成环返回 409。[RoleInheritanceRules](RoleInheritanceRules.java) 用显式的关系图计算可达角色。授权服务按目标系统筛选并以权限 ID 去重，审批在每次请求中读取最新授权结果。

## 与其他模块的关键联系

向授权配置提供可选角色与权限定义，实时授权读取直接分配和继承关系。

## 代码与接口

本包维护 [RbacRole](RbacRole.java)、[Permission](Permission.java)、[CatalogEntry](CatalogEntry.java) 及其 Repository。用例编排由 [ManagementQueryService](../organization/ManagementQueryService.java) 与 [ManagementMaintenanceService](../organization/ManagementMaintenanceService.java) 负责。

角色和权限分别使用 `/api/roles` 和 `/api/permissions`。Excel 功能清单通过 `/api/catalog-entries` 只读分页，原表动作和运行时授权分别维护。

`GET /api/roles/{roleId}/parents` 查询直接继承角色，`PUT` 和 `DELETE /api/roles/{roleId}/parents/{parentRoleId}` 修改直接继承边。管理端通过 `GET /api/employees/{employeeId}/effective-permissions?systemCode=...` 查询员工在指定系统的有效权限。

## 验收

已测试同码隔离、跨组织修改拒绝、重复创建、先解绑再删除，以及原表空值保留。继承规则、并发成环、实时撤权和组织重授的既有结果见 [双版本测试记录](../../../../../双版本测试记录.md)。代码与模块全面整理及测试重做见 [收尾清单](../../../../../收尾清单.md)，尚未执行。
