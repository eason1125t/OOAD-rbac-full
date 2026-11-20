# 授权分配与组织策略

## 核心功能及文件

本模块分别管理员工—角色、角色—权限的直接分配，以及组织对系统动作的停用、重开和来源角色重授。继承边交给 rolepermission，但新增继承复用本模块的授予合法性检查。两版主要区别是图快照类型；组织策略仍沿用共同 Service/JPA 方式。

| 文件 | 职责 |
| --- | --- |
| [AssignmentController](AssignmentController.java) / [AssignmentService](AssignmentService.java) | 两类关系 PUT/DELETE、事务编排和跨来源影响校验 |
| [EmployeeRole](EmployeeRole.java) / [RolePermission](RolePermission.java) 及仓储 | 直接分配组合键、防重复、引用与来源查询 |
| [OrganizationPermissionController](OrganizationPermissionController.java) / [OrganizationPermissionService](OrganizationPermissionService.java) | 策略查询、停用、重开及重授 |
| [OrganizationPermission](OrganizationPermission.java) / [PermissionRegrant](PermissionRegrant.java) | 策略状态与组织—直接来源角色—权限重授记录 |
| [PolicyStateVO](vo/PolicyStateVO.java) | 当前组织直接策略及已重授角色，不表示员工最终有效权限 |

## 直接分配核心函数

### `changeEmployeeRole`

READ_COMMITTED 事务中先验证管理员动作，再按“图锁 → 角色行 → 员工行”获取锁，校验两端管理范围。局部管理员对新增和撤销都要求角色归属属于员工部门/公司链。只有新增且关系尚不存在时，才用 `WorkspaceService.canSignIn` 拒绝无组织或停用员工，展开角色带来的全部权限来源，通过 `requireGrantableSources` 验证后保存。

撤销分配不应受当前停用策略阻拦；合法重复增删保持幂等。撤销只移除该直接关系，员工仍有其他有效路径时不会失去相应权限。

### `changeRolePermission`

锁顺序为“图锁 → 角色行 → 权限行”。通过 `dependingRoles(roleId)` 找该角色及所有依赖它的高级角色，再查询这些角色成员所属组织；操作者须能管理受影响范围。新增还将角色归属加入目标集合并逐组织验证，不能通过给低级角色补权限绕过继承影响检查。撤销删除直接关系，相应重授由数据库关系约束清理。

### `requireInheritanceGrant` 与 `requireGrantableSources`

新增继承时先取得 junior 可提供的来源，对 senior 的每个上游依赖角色计算“新增来源 = 提供来源 − 已有闭包”；只检查本次增加的影响，再将实际成员组织及 senior 归属纳入范围。

`requireGrantableSources` 用 `TreeMap<permissionId,List<sourceRoleId>>` 聚合直接来源，按权限 ID 升序加权限行锁，避免多权限请求因集合顺序不同而形成反向锁顺序。每一个直接来源分别交给 `requireGrantable` 检查本地授予资格。这里不保证最终员工权限已经恢复；同一来源覆盖全部适用 REOPENED 范围的最终判权由 `AuthorizationService.isAllowed/hasRegrantedSource` 完成。

`requireGrantable` 结合员工/角色组织链及系统归属链：适用 DISABLED 优先并返回分配冲突，全局管理员也不能绕过；局部管理员还受系统、权限归属和上级开放/重授条件限制；需要上级开放的权限时，目标链内任一 OPEN，或该直接来源在目标链内存在重授，可满足对应的写入资格。它是“能否授予”的写入规则，不是员工最终判权函数。

## 组织停用、重开与重授

| 函数 | 实现和边界 |
| --- | --- |
| `current` | 只读事务，验证全局管理资格及组织、系统、权限，返回指定组织直接状态；无记录默认 OPEN，不汇总其他组织策略 |
| `changeDisabled` | 按系统与 code 锁权限；停用保存 DISABLED，并清除该组织及直属子组织的旧重授；只有原状态 DISABLED 才能转成 REOPENED |
| `regrant` | 按“角色 → 权限”锁定，不取图锁；要求角色归属相容、目标为 REOPENED、角色直接持有权限、适用组织均未停用；缺失组合键时才保存 |
| `requireGlobal` | 通过 `ORGANIZATION_PERMISSION_WRITE` 限定为全局管理员 |

重新开放不是自动恢复全部普通账号。重授绑定**直接持有权限的来源角色**；若公司和部门都要求重授，同一个来源须同时满足这两处，不能公司重授 A、部门重授 B 后拼接通过。仅继承到权限的角色不能代替直接来源成为重授对象。

## 并发、调用及技术

继承、员工授角、角色授权及角色删除共同使用图写锁。组织停用仅锁权限，重授锁角色再锁权限。不能将所有写入笼统描述为同一种锁顺序，也不为解释简单而更换现有顺序。

调用链：`AssignmentController → AssignmentService → AuthorizationService / WorkspaceService / 图快照与各 JPA 仓储`；`OrganizationPermissionController → OrganizationPermissionService → 组织、系统、角色、权限、策略与重授仓储`。反向调用方包括继承服务的新增授权检查及实时授权对提交配置的读取。

特别技术包括 `@Transactional(READ_COMMITTED)`、JPA `PESSIMISTIC_WRITE`、复合主键、`TreeMap` 稳定锁顺序、`@Modifying` 的范围重授清理及数据库外键级联。`requireManaged` 当前覆盖公司及直属部门，不承诺任意深组织树。

API 为 `/api/employees/{employeeId}/roles/{roleId}`、`/api/roles/{roleId}/permissions/{permissionId}` 和组织系统动作策略资源。身份来自 `@AuthenticationPrincipal`，管理或归属越权为 403，缺失为 404，停用等分配规则冲突为 409。准确契约及现有验证见 [OpenAPI](../../../../../api/openapi.yaml)、[双版本测试记录](../../../../../测试/历史/双版本测试记录.md)。
