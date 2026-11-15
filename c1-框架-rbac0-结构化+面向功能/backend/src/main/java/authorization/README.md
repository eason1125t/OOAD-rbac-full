# 实时授权

状态：业务系统准入、审批判权和两类分配关系的管理入口授权已实现。参见 [C1 后端开发规划](../../../../../后端开发规划.md)。

## 应有功能

负责管理端准入、管理范围、业务系统准入、业务操作权限和组织停用。

## 实现方式

Spring Security 检查申请接口的登录和 CSRF，审批 Service 从记录查出所属系统后调用统一授权 Service。授权查询使用目标系统和动作编码，从 MySQL 读取最新关系。目标系统所属组织或其上级公司停用即拒绝；非超级管理员还受员工所属部门及公司停用限制，并且必须拥有一条直接角色授权路径。超级管理员可执行已配置且仍开放的动作。

## 代码入口

[SecurityConfiguration](SecurityConfiguration.java) 配置 Spring Security，[AuthorizationService](AuthorizationService.java) 根据当前管理范围和授权关系判定操作。系统准入复用 [WorkspaceService](../authentication/WorkspaceService.java)。

## 与其他模块的关键联系

业务模拟和管理入口调用统一授权 Service。登录导航与授权复用系统准入规则。已按 [组织权限停用与重新开放](../../../../../docs/组织权限停用与重新开放.md) 加入范围重授校验，C2 增加继承展开，C3 增加约束和可选角色激活判断。

## 故障处理与验收

审批前的数据库不可用返回 `503 AUTHORIZATION_UNAVAILABLE`。Redis 会话连接和命令故障返回 `503 SESSION_UNAVAILABLE`。使用隔离实例、真实 HTTP 会话和断连脚本确认待审批数据没有变化。

已覆盖跨部门专属系统拒绝、同码权限隔离、组织停用拦截超级管理员，以及经管理接口授撤权后的同会话多路径验证。
