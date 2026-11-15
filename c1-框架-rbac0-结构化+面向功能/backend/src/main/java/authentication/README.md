# 登录与会话

登录与会话已通过集成验证。参见 [C1 后端开发规划](../../../../../后端开发规划.md)。

## 应有功能

负责统一登录入口、当前身份、退出、CSRF 和 Redis 会话。登录结果包含用户导航所需信息。

## 实现方式

账号关联员工，密码使用 `PasswordEncoder` 哈希。会话保存可信员工 ID。使用同源 Cookie，写请求需 CSRF 令牌。登录成功后轮换会话 ID，后续写请求重新领取 CSRF 令牌。员工停用后，从下一次受保护请求起拒绝旧会话。演示测试凭据只在显式启用的演示环境里初始化。

## 代码入口

[AuthController](AuthController.java) 提供登录、退出和会话入口，[SessionAuthentication](SessionAuthentication.java) 管理会话，[WorkspaceService](WorkspaceService.java) 计算工作区，[DemoLoginAccounts](DemoLoginAccounts.java) 初始化演示账号。

## 与其他模块的关键联系

登录和当前身份使用工作区服务查询最新员工归属、管理员范围以及可进入的系统。审批授权使用同一会话中的可信员工 ID，并根据目标申请所属系统重新判权。账号初始化复用模块 1 的合成员工，V3 迁移新增账号和组织默认系统标记。

## 验收

通过隔离 MySQL 和 Redis 上的 HTTP 测试验证登录、错误凭据和待匹配员工拒绝、退出、过期、停用、旧 CSRF 令牌失效、工作区范围、默认导航和 Redis 断连时的 503 响应。普通员工资料创建与演示账号初始化独立。
