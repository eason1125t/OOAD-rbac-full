# 应用启动与模块装配

## 职责和范围

本包只负责 Spring Boot 启动及包扫描，不承担登录、授权或业务事务。两版入口代码相同，运行身份由各自 Maven 制品和 `application.yaml` 区分。

## 关键文件与函数

| 文件 / 函数 | 实现与作用 |
| --- | --- |
| [RbacApplication.main](RbacApplication.java) | 通过 `DefaultApplicationArguments` 识别 `--init-demo`、`--import-excel`；出现任一项时设置 `WebApplicationType.NONE`，否则启动 Servlet Web 服务 |
| `@SpringBootApplication(scanBasePackages=...)` | 显式扫描九个同级业务包；入口位于 `app`，仅用默认扫描无法发现同级模块 |
| `@EntityScan` / `@EnableJpaRepositories` | 分别注册实体和 Spring Data JPA 仓储，避免组件扫描和持久化扫描范围不一致 |
| `UserDetailsServiceAutoConfiguration` 排除项 | 避免生成默认用户，使认证统一由项目自己的 `SessionAuthentication` 处理 |

## 启动顺序与调用关系

1. JAR、IDE 通常从 `main` 启动并读取配置及环境变量。现有 `@SpringBootTest(classes=RbacApplication.class)` 默认以配置类装配测试上下文，不调用 main；是否使用 main 由测试配置决定。
2. 自动配置数据源，Flyway 执行尚未应用的迁移，Hibernate 按 `ddl-auto=validate` 核对映射。启动代码不手动建表。
3. Spring 注册 Controller、Service、Repository、密码编码器及日志切面。Servlet 模式才注册请求安全链。
4. [InitializationCommand](../initialization/InitializationCommand.java) 在 `@Order(10)` 运行，只有显式命令才导入或初始化。
5. 开启演示账号配置时，[DemoLoginAccounts](../authentication/DemoLoginAccounts.java) 以 `@Order(20)` 在演示员工创建之后准备账号。
6. Web 模式继续接受 HTTP 请求；非 Web 模式不监听端口。`main` 没有显式 `close` 或 `System.exit`，因此不把“非 Web”描述为主动关闭上下文。

## 配置与特别技术

[application.yaml](../../resources/application.yaml) 使用环境变量注入 MySQL 凭据、Redis 连接、端口、Cookie 及会话命名空间。Spring Session 将服务端会话存入 Redis；Flyway 管理 V1–V8，JPA 只校验结构。

两版分别构建和启动：功能版使用 `ooad_rbac1_func` 和 `rbac1:func:session`，对象版使用 `ooad_rbac1_oo` 和 `rbac1:oo:session`。Cookie 名和路径也分别配置。不能同时把两版指向同一业务库来声称数据隔离。

## 验证与边界

启动与单元验证命令见 [阶段运行说明](../../../../../README.md)。账号、原表导入分别由 authentication、initialization 说明。数据库或映射启动失败应先核对迁移及配置，不修改已执行迁移来绕过校验。Compose、Swarm、镜像与课程环境交付仍属于收尾第 5 项。
