# 原表导入与演示数据初始化

## 核心功能与入口

本模块提供显式命令，负责三份课程 Excel 的原子导入，以及演示组织、员工、角色、权限和申请的幂等准备。它不提供 HTTP 上传，也不通过管理 Controller 逐行写库。两版采用同一实现，数据写入各自配置的 MySQL 库。

| 文件 / 函数 | 职责 |
| --- | --- |
| [InitializationCommand.run](InitializationCommand.java) | `@Order(10)` 解析 `--import-excel=<目录>` 或 `--init-demo`，拒绝同时使用，输出数量摘要 |
| [WorkbookReader.read](WorkbookReader.java) | 使用 Apache POI 只读打开工作簿，校验表头、处理合并单元格并保留原始行号 |
| [InitializationService.importWorkbooks](InitializationService.java) | 对三表解析、校验和保存建立同一事务；`rollbackFor=IOException.class` 保证检查异常也回滚 |
| `initializeDemo` | 在图写锁保护下补齐演示场景，保留已有用户变更 |
| [Flyway 迁移](../../resources/db/migration) | 由 Spring Boot 启动时执行；初始化 Service 不自行建表 |

## Excel 导入的核心实现

1. `importWorkbooks` 使用既定文件名、工作表名、表头行号和列清单；输入表头不匹配立即失败。
2. `WorkbookReader.read` 通过 `WorkbookFactory`、`try-with-resources` 和 `DataFormatter(Locale.ROOT)` 读取文本，空值保留 `null`，全空行跳过。功能清单仅对表头之后、单列且列号小于 2 的合并区域展开首行值，不任意复制所有合并单元格。
3. `importOrganizations` 先建立名称索引并核对原上级。员工分公司列对应名称或无上级行建模为 COMPANY；其他行须找到已建公司父级才成为 DEPARTMENT。原表上级文本单独保留，不强行等同授权父组织 ID。
4. `saveSourceOrganization` 以 UTF-8 名称的 `UUID.nameUUIDFromBytes` 构造稳定 `xlsx-...` 来源代码，查找已有组织后逐字段比较。稳定键支持重跑，字段冲突不静默覆盖。
5. `resolveEmployeeOrganization` 先匹配公司，再尝试部门名及“分公司-部门”组合；只有唯一且父公司正确的候选才能形成正式归属，否则员工保留待匹配状态，不猜测组织。
6. 员工按 username 建索引；新行批量保存，已有行使用 `employeeValues` 比较来源字段。原表结构冲突或数据不一致导致整批回滚。
7. `importCatalog` 用局部 `FeatureKey(system,module,feature)` 定位清单来源，保留描述、五类角色动作文本和空值。清单不自动转成运行时角色授权。

`SourceRow.required` 只用行列位置说明缺失，不输出员工姓名或电话。导入统计只含数量；初始化命令的控制台摘要与正式业务审计日志用途不同。

## 演示初始化：幂等与保留变更

`initializeDemo` 先调用 `inheritance.lockWrites`。若没有初始公司标记，`initializeDemoBaseline` 创建基础组织、员工、管理员范围、系统和初始授权；随后准备申请动作、申请经办角色、继承演示角色、新账号对应员工及四个系统的查看角色。

- `initializeDemoRequests` 补齐 `request:read` 及写动作定义；系统完全没有申请时才创建历史样例，已有审批结果保留。
- `createDemoRequestRole` 按同组织同名角色判断是否已初建，避免重复授角和直接授权。
- `initializeDemoRoleScenarios` 仅在首次建立继承审批人时建立示例继承边，重跑不会恢复被管理员删除的边。
- `seedInheritedTestRole` 初次创建展示主管时只配置继承，便于区分直接权限和继承权限。
- `createDemoReadRole` 首次创建时配置查看权；后续人工撤权与组织停用、重开策略保留。重开时仍由管理员对直接权限来源角色完成显式重授。

因此“幂等初始化”不等于每次恢复全部演示授权。演示登录账号由后续 `DemoLoginAccounts @Order(20)` 处理，初始化员工资料与创建密码账号是不同步骤。

## 迁移与调用关系

V1–V5 为 C1 基础结构与组织重授，V6 增加继承边和图锁表，V7 扩展申请人、状态及逻辑删除，V8 建立系统申请查看权限定义。已执行迁移不改写。历史申请的申请人可为空。

`app → InitializationCommand → InitializationService → WorkbookReader + 各业务 JPA 仓储`。Service 跨 organization、rolepermission、assignment、authorization、simulation 准备基础数据；`DemoLoginAccounts` 随后复用员工创建凭据。测试可直接调用 Service 在隔离环境中准备数据，不经 HTTP 会话。

## 特别技术、运行与验证

本模块使用 POI 的格式文本和受限合并展开、稳定名称 UUID、`LinkedHashMap` 来源索引、局部 record 复合键、JPA `saveAll`、覆盖检查异常的事务回滚以及继承图写锁。

命令示例和环境变量见 [阶段 README](../../../../../README.md)；原表约定见 [Excel 说明](../../../../../../excel/README.md)。现有 `InitializationTest`、`WorkbookReaderTest` 和 `InitializationCommandTest` 覆盖解析、冲突、重复导入和演示场景。历史计数为员工 10,000、组织目录 111、清单 359、待匹配员工 54；这些是既有原表基线，不是本次重新导入结果。课程集群原表、只读挂载和一次性初始化的交接要求见 [部署交接](../../../../../部署交接文档.md)，实际输入版本与任务仍待配置验证。
