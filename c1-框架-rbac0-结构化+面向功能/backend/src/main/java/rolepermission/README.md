# 角色与权限

状态：角色和权限定义维护、关联查询和原表清单查询已实现。参见 [C1 后端开发规划](../../../../../后端开发规划.md)。

## 应有功能

负责角色和运行时权限定义的 CRUD，以及 Excel 功能权限清单的只读查询。

## 实现方式

业务权限按系统与权限码标识。全局共享定义由超级管理员维护，局部定义按归属校验。编辑只改变名称。角色仍被分配、权限仍被分配或有组织策略引用时，删除返回 `409 RESOURCE_CONFLICT`。清单原动作文本和空值用于展示。

## 与其他模块的关键联系

向授权配置提供可选角色与权限定义，实时授权读取分配和定义。C2 新增继承关系，C3 新增约束配置。

## 代码与接口

本包维护 [RbacRole](RbacRole.java)、[Permission](Permission.java)、[CatalogEntry](CatalogEntry.java) 及其 Repository。用例编排由 [ManagementQueryService](../organization/ManagementQueryService.java) 与 [ManagementMaintenanceService](../organization/ManagementMaintenanceService.java) 负责。

角色和权限分别使用 `/api/roles` 和 `/api/permissions`。Excel 功能清单通过 `/api/catalog-entries` 只读分页，原表动作和运行时授权分别维护。

## 验收

已测试同码隔离、跨组织修改拒绝、重复创建、先解绑再删除，以及原表空值保留。
