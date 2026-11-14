# C1 · RBAC0

队友可按 [部署运行说明](./部署运行说明/README.md) 用 Docker Compose 启动后端、MySQL 和 Redis。下文保留直接使用 Java 和 Maven 的开发方式。

## 运行后端与初始化

使用 Java 21、Maven 3.9、Spring Boot 3.5.16 和 MySQL 8.4。IntelliJ IDEA 可直接打开 [backend/pom.xml](./backend/pom.xml)。在 Navicat 的 `ooad_rbac` 连接下确认 `ooad_rbac0` 库及本地项目账号可用。启动后 Flyway 写入表结构与迁移历史，Hibernate 校验映射。

从 C1 的 `backend` 目录运行。使用 Docker Desktop 启动 MySQL 和 Redis，按本地端口设置环境变量。数据库用户和密码使用本地配置，勿将密码写进项目文件。

```powershell
$env:RBAC_DB_URL = 'jdbc:mysql://localhost:3306/ooad_rbac0'
$env:RBAC_DB_USER = '你的 MySQL 项目账号'
$env:RBAC_DB_PASSWORD = Read-Host 'MySQL 密码' -MaskInput
$env:RBAC_REDIS_PORT = '6379'
mvn package -DskipTests
java -jar target/rbac0-0.1.0-SNAPSHOT.jar
```

普通启动在 `http://localhost:8080` 提供接口。Redis 主机默认 `localhost`，可通过 `RBAC_REDIS_HOST`、`RBAC_REDIS_PASSWORD` 配置。会话空闲超时默认 30 分钟，可通过 `RBAC_SESSION_TIMEOUT` 调整。使用 HTTPS 时设置 `RBAC_SESSION_SECURE=true`。

需要演示数据和测试账号时，在独立的演示库运行如下命令。账号为 `super-admin`、`company-a-admin`、`engineering-admin`、`engineering-employee`，各账号初始密码使用本地输入（例如 `1234`）。已经初始化的环境保留后续配置和现有密码哈希。

```powershell
$env:RBAC_DEMO_ACCOUNTS_ENABLED = 'true'
$env:RBAC_DEMO_PASSWORD = Read-Host '演示账号初始密码' -MaskInput
java -jar target/rbac0-0.1.0-SNAPSHOT.jar --init-demo
```

课程对照验收时，在受控环境显式导入三份工作簿。命令从 `backend` 目录运行，`../../excel` 指向项目根目录下的原表目录。各命令执行完成后进程退出。

```powershell
java -jar target/rbac0-0.1.0-SNAPSHOT.jar '--import-excel=../../excel'
```

同内容可重复导入。源字段与已有数据冲突时报告表和行号并回滚。部门目录保留原表上级名称，组织归属只认本公司下唯一匹配的部门。权限清单保留原文和空值，实际授权由角色与权限的配置产生。

## 验证

准备独立的 MySQL 测试库 `ooad_rbac0_test`，将 `RBAC_DB_URL` 指向该库，显式设置测试 Redis 的 `RBAC_REDIS_PORT`，再运行 `mvn verify`。数据源指向其他数据库时只运行单元测试。测试库无需运行 `--init-demo`。首次演示初始化用例在测试事务中写入并回滚，HTTP 集成用例使用唯一标识的合成数据。JaCoCo HTML 生成在 `backend/target/site/jacoco/index.html`。

在隔离 MySQL 和 Redis 上运行 `mvn verify`，29 项测试覆盖 Flyway V1 至 V5、三表导入、会话、审批、授撤权、组织策略并发及管理维护。手动联调时，先调用 `GET /api/auth/csrf` 并保存 Cookie，用响应的令牌在 `X-CSRF-TOKEN` 请求头提交登录。登录后使用新 Cookie，写请求前重新领取 CSRF 令牌。请求样例见 [首批接口验收](./api/首批接口验收.md)，数据库结构见 [数据库设计](./docs/数据库设计.adoc)。

### 依赖故障复验

在隔离的 MySQL 实例中新建空库 `ooad_rbac0_fault`。使用当前后端 JAR，在 `backend` 目录配置 `RBAC_DB_USER`、`RBAC_DB_PASSWORD` 和 `RBAC_DEMO_PASSWORD`，再执行下列命令，端口按隔离实例修改。脚本初始化合成演示数据并启动临时后端，通过本机代理逐个断开依赖连接。验证 HTTP 503 错误码，并在依赖恢复后确认对应申请仍为待审批。

```powershell
python scripts/verify_dependency_failures.py --mysql-port 33316 --redis-port 36379
```

## 演示申请审批

Flyway V4 新增模拟申请表。运行 `--init-demo` 后，四个演示系统各有六条申请；已初始化的库可重跑，已有申请和审批结果会保留。用工程员工账号登录后，先调用 `GET /api/business-systems/demo-engineering-app/requests?page=1&pageSize=20`，再取一条待审批申请的 ID 和当前会话的 CSRF 令牌，发送 `POST /api/requests/{requestId}/approve`。成功返回 `authorization=GRANTED`、`execution=SUCCEEDED`。用同一账号审批公司系统申请时预期返回 403，重复审批已成功的工程申请预期返回 409。

## 管理数据查询

使用管理员 Cookie 调用 `GET /api/employees`、`/api/companies`、`/api/departments`、`/api/roles`、`/api/permissions` 或 `/api/catalog-entries`。授权页面可另外查询 `GET /api/employees/{employeeId}/roles` 和 `GET /api/roles/{roleId}/permissions`。分页默认 `page=1&pageSize=20`，`total` 是可见范围内的记录数。员工电话已遮盖，Excel 权限清单保持原文本和空值。具体字段及错误响应见 [OpenAPI](./api/openapi.yaml)。

## 后续模块

员工、角色和权限支持新增、详情、编辑及删除操作。员工删除采用停用，并保留历史分配；调岗前须先解除现有角色和管理员配置。新增员工只创建资料，演示登录凭据仍沿用初始化流程。角色和权限编辑只改展示名称，被引用时需先解除相关关系。授权流程用管理员和员工各自的会话按 [首批接口验收](./api/首批接口验收.md) 复现。接下来复验课程环境，并接入最小可运行的管理端和业务模拟端。
