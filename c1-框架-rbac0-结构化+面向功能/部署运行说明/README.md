# C1 部署运行说明

本指南用于队友在自己的电脑或 Linux 主机上运行 C1（RBAC0）。Compose 启动后端、MySQL、Redis，并用 Flyway 迁移数据库。目前可以通过 API 演示登录、管理、授撤权和审批。权限管理端及业务模拟端页面待开发。

## 1. 准备软件

| 软件 | 用途 |
| --- | --- |
| Docker Desktop | Windows、macOS 本机启动 Linux 容器。Linux 主机可用 Docker Engine 和 Compose 插件。先确认 `docker version` 能看到 Server，`docker compose version` 可运行。 |
| Git | 克隆项目和获取更新。私有仓库需要对应的 GitHub 访问权。 |
| Apifox 桌面版 | 调试登录、分配和审批接口，按环境选择当前后端地址。 |
| Navicat for MySQL | 查看 Flyway 迁移历史、员工和分配数据，按需要加入项目团队。 |
| IntelliJ IDEA / StarUML | 可选。IDEA 用于后端开发，StarUML 用于查看 `docs/design/` 下的可编辑图源。容器运行时无需在宿主机另装 Java、Maven、MySQL 或 Redis。 |

[Docker Compose 安装说明](https://docs.docker.com/compose/install/)

## 2. 在自己的主机启动

启动 Docker，在终端运行以下命令。已有仓库的队友可以从进入目录这一步开始。

```sh
git clone https://github.com/eason1125t/OOAD-rbac-full.git
cd OOAD-rbac-full
cd "c1-框架-rbac0-结构化+面向功能/部署运行说明"
```

Windows PowerShell 执行 `Copy-Item .env.example .env`，macOS 或 Linux 执行 `cp .env.example .env`。编辑 `.env`，为 `MYSQL_ROOT_PASSWORD` 和 `RBAC_DB_PASSWORD` 分别填本机口令。建议使用字母、数字等无需转义的字符。文件已被 Git 忽略。后端数据库用户固定为 `rbac`，库名为 `ooad_rbac0`。默认接口端口为 `8080`，Navicat 连接端口为 `3307`，端口冲突时修改 `.env` 中对应的值。

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=80 backend
```

第一次启动需要下载基础镜像及 Maven 依赖。等待后端日志出现 `Started RbacApplication` 后，访问 `http://127.0.0.1:8080/api/auth/csrf`。看到 HTTP 200 和 JSON 令牌表示 Web 服务与 Redis 会话已接通。确认数据库迁移可在 Navicat 查看 `flyway_schema_history` 的 V1–V5。MySQL 和后端默认绑定宿主机回环地址，Redis 只在 Compose 网络内可达。

### 初始化演示账号

容器正常运行后执行一次。以下密码是本地合成数据演示值，团队共享主机请改用另行分发的口令。重跑命令会保留已有密码哈希与审批结果。

```sh
docker compose run --rm -e RBAC_DEMO_ACCOUNTS_ENABLED=true -e RBAC_DEMO_PASSWORD=1234 backend --init-demo
```

| 账号 | 用途 |
| --- | --- |
| `super-admin` | 管理全局关系和组织策略 |
| `company-a-admin` | 验证公司及下属部门的管理范围 |
| `engineering-admin` | 验证部门管理范围 |
| `engineering-employee` | 验证业务审批及同会话实时撤权 |

### 课程原表可选导入

日常联调用合成数据即可。课程对照时，从团队约定的受控渠道取得原始工作簿，按 [Excel 目录说明](../../excel/README.md) 放到仓库根的 `excel/`。在当前部署目录执行下列命令。挂载仅供该次导入且为只读，工作簿无需放入镜像。

```sh
docker compose run --rm -v "../../excel:/import:ro" backend --import-excel=/import
```

## 3. 用 Apifox 复验接口

加入「ooad项目」团队的 `OOAD RBAC` 后，选 `C1-RBAC0` 模块及本地调试环境，把前置 URL 指向 `http://127.0.0.1:8080` 或实际端口。仓库 [OpenAPI](../api/openapi.yaml) 包含当前 34 个操作。若团队项目仍只显示早期接口，可把此文件导入自己的临时项目做联调，共享项目由负责人同步。 [Apifox OpenAPI 导入说明](https://docs.apifox.com/import-openapi-swagger)

1. 用 `GET /api/auth/csrf` 取得匿名 Cookie 和响应中的 `token`。保持同一 Cookie，给 `POST /api/auth/login` 加 `X-CSRF-TOKEN` 头，JSON 请求体填 `username`、`password`。
2. 登录后使用轮换后的 `SESSION`，调用 `GET /api/auth/me` 查看身份。再次调 `GET /api/auth/csrf` 后再发送 PUT、DELETE、审批或退出请求。
3. 用工程员工查询 `GET /api/business-systems/demo-engineering-app/requests`，挑一条 `PENDING` 记录调用 `POST /api/requests/{requestId}/approve`。成功应返回鉴权通过和执行成功。
4. 用两个独立 Cookie 容器分别登录管理员和员工，按 [首批接口验收](../api/首批接口验收.md) 复现授予、审批、撤销。撤权后用一条新的待审批记录验证 403。

## 4. 用 Navicat 查看数据库

本机运行 Compose 的队友先创建一个 MySQL 连接，名称可使用 `ooad_rbac_c1_local`。

| 字段 | 本地配置 |
| --- | --- |
| 主机 / 端口 | `127.0.0.1` / `3307`，或者实际的 `MYSQL_HOST_PORT` |
| 用户 / 密码 | `rbac` / 自己 `.env` 中的 `RBAC_DB_PASSWORD` |
| 数据库 | `ooad_rbac0` |

测试连接后，可看 `flyway_schema_history`、`employee_role`、`role_permission`、`organization_permission` 和 `simulated_request`。演示授撤权时通过 API 提交，再在 Navicat 刷新结果。

负责人随后会邀请队友加入 Navicat 项目团队。收到邀请后登录 Navicat Cloud 并打开团队项目。共享连接的主机、账号和访问方式以负责人提供的配置为准。共享连接中的 `127.0.0.1` 始终指向当前机器，团队项目成员权限和 MySQL 登录权限分别配置。 [Navicat 连接配置](https://www.navicat.com/manual/online_manual/en/navicat_18/win_manual/con_gen_settings.html)

## 5. 远程主机和常用命令

Linux 主机也可在同一目录运行上述 Compose 命令。若从自己电脑调试远程实例，保持端口绑定在远端回环地址，在自己电脑发起 SSH 转发，目标主机及用户换成实际配置。

```sh
ssh -N -L 18080:127.0.0.1:8080 -L 13307:127.0.0.1:3307 user@your-host
```

保持 SSH 会话开启。Apifox 使用 `http://127.0.0.1:18080`，Navicat 使用 `127.0.0.1:13307`。如果在远程主机改过 Compose 宿主机端口，对应修改 SSH 命令右端端口。当前配置用于 C1 单机运行，C2 的 Docker Swarm 文件随 C2 交付。

| 操作 | 命令或检查 |
| --- | --- |
| 更新后端 | `git pull --ff-only` 后运行 `docker compose up -d --build` |
| 查看错误 | `docker compose ps`，`docker compose logs --tail=100 backend mysql redis` |
| 停止并保留数据 | `docker compose down`。MySQL 与 Redis 使用命名卷，之后 `up` 可复用。 |
| 服务起不来 | 确认 Docker 正在运行、端口未占用、`.env` 已填好。已有 MySQL 数据卷的用户和密码以数据库内当前配置为准，修改 `.env` 不会自动修改已有数据库账号。 |
| 基础镜像拉取超时 | 检查 Docker Desktop 或 Docker Engine 的网络设置。也可在 `.env` 用 `RBAC_MAVEN_IMAGE` 和 `RBAC_JAVA_IMAGE` 指向可访问的官方镜像副本。 |
| 写请求返回 403 | 检查请求是否同时使用当前会话和登录后重新领取的 CSRF 令牌，再检查当前账号是否有业务权限。 |

部署后的测试和现有功能见 [C1 README](../README.md)。使用真实 Excel 的课程环境与两套前端仍待联调。
