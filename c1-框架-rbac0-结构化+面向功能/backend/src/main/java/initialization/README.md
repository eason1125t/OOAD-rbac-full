# 数据与初始化

状态：已实现并通过本地验收。参见 [C1 本地验收记录](../../../../../docs/本地验收记录.md)。

## 应有功能

负责 Flyway、JPA 表映射、三份 Excel 导入、演示组织与分配初始化。

## 实现方式

启动时迁移并校验 JPA。显式执行 `--import-excel` 或 `--init-demo`。保留源字段和空值，唯一匹配才关联组织。相同输入可重跑，冲突整批回滚。V1–V5 依次建立基础表、保留原表上级、增加登录账号与默认系统、增加模拟申请并支持组织重授。

## 代码入口

[InitializationCommand](InitializationCommand.java) 解析启动参数，[InitializationService](InitializationService.java) 管理导入事务，[WorkbookReader](WorkbookReader.java) 读取工作簿。Flyway 脚本位于 [resources/db/migration](../../resources/db/migration)。

## 与其他模块的关键联系

为登录提供员工，为组织与员工、角色与权限和授权配置提供基础记录。与业务模块分别维护登录凭据和模拟申请。

## 验收

本地已验证 Flyway V1–V5、重复导入和事务回滚。演示数据可用 Compose 初始化，课程原表通过只读挂载导入。课程库检查项见 [部署运行说明](../../../../../部署运行说明/README.md)。
