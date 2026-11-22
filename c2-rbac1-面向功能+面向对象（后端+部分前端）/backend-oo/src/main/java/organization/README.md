# 组织、员工及跨模块管理用例

## 核心职责

本包不仅映射公司、部门和员工，还承载管理端跨角色、权限、清单与关系的查询和维护入口。公司、部门目录当前只读；员工删除 API 实际执行停用；角色授权关系写入由 assignment 承担，继承边写入由 rolepermission 承担。

两版在此保留相同的 Service/JPA 实现；对象版并未将本包整体改为 DDD。继承算法差异经各版 `RoleInheritanceStore` 接入。

## 核心文件与函数

| 文件 / 函数 | 实现重点 |
| --- | --- |
| [ManagementQueryController](ManagementQueryController.java) | 校验分页与路径，将 Page/实体映射为公开 VO，设置 `no-store` |
| [DirectoryVisibilityService](DirectoryVisibilityService.java) | 集中普通员工/管理员读取范围；ids与上级策略policyIds分开 |
| [DirectoryQueryService](DirectoryQueryService.java) | 员工与部门模式筛选；Specification同时约束列表和count，批量hasChildren |
| [ManagementQueryService.scope](ManagementQueryService.java) | 委托统一可见范围，原管理详情/授权查询仍要求管理员 |
| `companies/roles/permissions/catalog` | 各按管理员可见范围查询；原表清单为管理员核对目录，不等同员工业务权限 |
| `employeeRoles/roleParents/rolePermissions/inheritedRolePermissions` | 分别查询员工直接角色、一跳继承、直接权限和继承来源配置 |
| `effectivePermissions` | 核对管理员能查看目标员工及目标系统后，调用统一授权入口计算实际权限，再在内存切页 |
| [ManagementMaintenanceController](ManagementMaintenanceController.java) | 接收受 Bean Validation 保护的可写字段，调用维护 Service，创建返回 201 和 Location |
| [ManagementMaintenanceService](ManagementMaintenanceService.java) | 以 READ_COMMITTED 事务校验管理范围、锁定资源并保护已有关系 |
| [Employee](Employee.java) / [Organization](Organization.java) 及仓储 | 保存正式归属、来源字段和启用状态，供多模块共用 |

## 查询实现与三个权限概念

管理读取的 `scope` 委托 DirectoryVisibilityService，对 GLOBAL 开放全局目录；局部管理员可见本级，公司管理员增加直属部门。部门管理员的 `policyIds` 额外包含父公司，以便显示上级开放的权限，这不表示能修改上级资源。

目录分页主要在数据库执行，列表和总数接受同一范围条件。全局员工列表含待匹配记录；局部列表仅包含明确归属的员工。部门原表目录查询还保留 `xlsx-%` 来源行，因此原表中的某些行即使被建模为 COMPANY，仍可在原表目录核对。

- `rolePermissions`：该角色直接被授予的权限。
- `inheritedRolePermissions`：对角色展开全部来源，移除根角色自身后，按权限 ID 去重查询来源角色的权限，并应用管理可见范围。这是配置清单，不是员工最终生效结论。
- `effectivePermissions`：对指定员工、指定系统执行准入、继承与组织停用/重授判断；先完整计算再使用 `PageImpl` 内存分页。超过结果的页为空，但 total 保持真实总数。

`requireEmployeeVisible/requireRoleVisible` 区分不存在的 404 与越范围的 403。共享角色的读取和写入权限不同：局部管理员可以查看相关共享定义，修改仍须全局管理员。

## 维护实现与事务边界

1. 每个用例通过 `scope(actor)` 实时取得管理资格；`requireManaged` 对全局与本级/直属部门作不同处理，空归属的共享定义只允许 GLOBAL。
2. `createEmployee` 只建资料，不创建登录凭据。`updateEmployee` 先锁员工并复核其管理员配置；调岗须同时有原、新组织管理权，已有角色或管理员配置时返回分配冲突，要求先解绑。
3. `Employee.updateProfile` 只更新非 null 字段，岗位/电话的空白字符串清空可选值，用户名和原表来源字段不变。
4. `disableEmployee` 保留实体及历史引用。安全过滤器在下一受保护请求重新读员工，因此旧会话不能继续操作。
5. `renameRole` 使用角色锁及 `saveAndFlush`，这是在当前边界捕获名称唯一约束的既有例外；不机械删除 flush。
6. `deleteRole` 先取得图写锁再锁角色，检查员工分配、直接权限和双向继承引用，任一存在则 409，否则物理删除角色。
7. `createPermission` 同时检查权限归属及系统管理范围，管理权限的空系统只允许全局管理员。重命名只改展示名；删除前锁权限并检查角色分配、组织策略引用。

员工、角色、权限的悲观行锁与 assignment 的写入顺序协作；不得为了简化代码跳过范围或引用检查。

## 调用与被调用

`管理页面 → QueryController / MaintenanceController → 对应 Service → AuthorizationService + 各 JPA 仓储 + RoleInheritanceStore`。

本包的员工和组织仓储被登录工作区、角色分配、实时授权、初始化共用。维护 Controller 复用查询 Controller 的公开响应映射；涉及关系的写用例仍调用其专属模块。API 不直接序列化 JPA 实体。

## 技术、展示与验证

[ManagementPageDTO](dto/ManagementPageDTO.java) 使用 1 基页码和最大 100 的页容量，内部转为 0 基 `PageRequest`，按 ID 排序。`@Valid/@Positive/@NotBlank` 校验格式，Service 判断业务范围。组织和清单同名字段通过 CloneFactory 编译期映射；员工、角色、权限 record 使用显式字段构造，不把所有转换都声称为自动映射。

`ManagementQueryController.employee` 遮盖电话：空值保持 null，短号全遮盖，长度至少 9 时保留前 3 后 4。脱敏属于响应映射，数据库原字段不因此改写。

第4步目录扩展已经完整后端验证，见 [目录验证](../../../../../测试/README.md)；前端与集群未重跑。准确路径和响应见 [共同 OpenAPI](../../../../../api/openapi.yaml)。现有 `AuthSessionTest` 及前端目录、角色权限测试验证范围、拒绝与分页；本次源码说明整理不代表重新完成全量数据库或浏览器验收。历史证据及后续测试重做见 [双版本测试记录](../../../../../测试/README.md)、[开发规划与进度](../../../../../docs/项目总结.md)。

## 第四步：正式树、员工检索与兼容

GET /api/departments 默认 directory 保留 DEPARTMENT 或 xlsx-% 来源行，仅管理员；显式 view=tree 使用真实parentId。无父级/有效搜索词时默认可见根；parentId只查直接子级，与任何显式rootsOnly互斥。rootsOnly=false为范围内平铺，仅q在范围内搜索，rootsOnly=true+q仅查根。hasChildren只统计可见直接子级，与q对子级的匹配无关；按本页ID批量查询，不逐节点发SQL。普通员工根可以是本部门，parentId保留真实值。

GET /api/employees 支持departmentId（仅DEPARTMENT直接归属）、q、原分页。普通员工只看本组织启用员工及id/username/name/organizationId；直接属于公司的普通员工不自动看到下属部门。部门管理员看本部门，公司管理员看公司与直属部门，全局管理员保留待匹配员工核对；管理员旧字段及电话遮盖不变。公司节点用于树分组展开，不可作为departmentId筛选值。

DirectoryQueryService将范围AND部门AND（姓名OR账号）传入JPA Specification，Page内容和count同条件。q去首尾空白，LIKE显式用!转义!/%/_；不以全量加载后过滤实现隔离，不宣称contains可由普通B-tree加速。目录与员工DAO移除了无调用的旧固定目录方法，原公司/角色/权限/关系查询共用DirectoryVisibilityService，没有保留第二套范围算法。

HTTP输入由DTO约束，目录参数组合及非部门ID由Service抛VALIDATION_FAILED映射400；未知组织404、存在但越界403。普通用户的员工详情/角色/权限和所有写路由仍由原管理资格保护。权限组撤销后下一请求会降为普通投影和范围；不取消已通过检查的在途请求。维护写入的组织规则、锁顺序和申请/角色领域不改。

新增VO为不可变record和派生字段，显式映射；旧原表OrganizationVO仍走CloneFactory。未新增表、迁移、索引或组织层级写入，权限语义仍为公司→直属部门。运行、RED/GREEN及待办见 [第四步验证](../../../../../测试/README.md)。
