# C1 部署运行说明

本指南以 Windows PowerShell 为例。首次运行按顺序完成准备软件、启动 Compose 和初始化演示账号，再选择用浏览器或 Apifox 验收。Compose 启动后端、MySQL 和 Redis，前端在另一个终端启动。MySQL 由容器创建，Flyway 自动建表，宿主机无需另装 MySQL 或 Redis。

## 1. 准备软件

| 软件 | 用途 |
| --- | --- |
| Docker Desktop | Windows 使用 WSL 2 后端并运行 Linux 容器。安装后先打开 Docker Desktop，等引擎启动。macOS 可直接使用 Docker Desktop，Linux 主机可用 Docker Engine 和 Compose 插件。 |
| Git | 克隆项目和获取更新。私有仓库需要对应的 GitHub 访问权。 |
| Node.js 22.12+ 或 24 | 启动前端、构建前端和运行浏览器测试。只用 Apifox 调后端接口时可跳过。 |
| Apifox 桌面版 | 可选。向真实后端发送请求，验证登录、授撤权和审批。 |
| Navicat | 可选。查看 MySQL 表和关系数据，也可直接用容器内的 MySQL 命令行。 |
| JDK 21、Maven 3.9 | 在宿主机跑后端自动化测试和 JaCoCo 时使用。Compose 构建后端镜像自带编译环境。 |

打开 PowerShell 验证 Docker 引擎和 Compose。配置前端时另用 `node --version` 确认 Node.js。

```powershell
docker version
docker compose version
```

`docker version` 应同时显示 Client 和 Server。Windows 若提示 WSL 2 不可用，先按 Docker Desktop 的提示完成 WSL 配置。[Windows 安装指南](https://docs.docker.com/desktop/setup/install/windows-install/) · [Docker Compose 安装说明](https://docs.docker.com/compose/install/)

## 2. 拉取代码并启动后端、MySQL 和 Redis

已有仓库的队友从进入 C1 部署目录这一步开始。

```sh
git clone https://github.com/eason1125t/OOAD-rbac-full.git
cd OOAD-rbac-full
cd "c1-框架-rbac0-结构化+面向功能/部署运行说明"
```

首次运行时复制配置模板。保留已有的 `.env`，需要修改时直接打开它。macOS 或 Linux 可在首次运行时用 `cp .env.example .env`。

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
```

| `.env` 字段 | 填写方式 |
| --- | --- |
| `MYSQL_ROOT_PASSWORD` | 自行设置 MySQL 管理员密码 |
| `RBAC_DB_PASSWORD` | 自行设置应用账号 `rbac` 的密码，Navicat 也用这个密码 |
| `MYSQL_HOST_PORT` | 默认 `3307`，供宿主机上的 Navicat 使用 |
| `RBAC_HTTP_PORT` | 默认 `8080`，供 Apifox 和前端代理使用 |
| `RBAC_SESSION_SECURE` | 本地 HTTP 保持 `false` |

两种数据库口令分别设置。建议用字母和数字，避免额外转义。真实 `.env` 已被 Git 忽略。端口冲突时修改对应的宿主机端口，后端在容器网络内仍通过 `mysql:3306` 和 `redis:6379` 访问依赖。

```powershell
docker compose config --quiet
docker compose up -d --build --wait --wait-timeout 120
docker compose ps
docker compose logs --tail=80 backend
curl.exe -i http://127.0.0.1:8080/api/auth/csrf
```

首次构建会下载基础镜像与 Maven 依赖，编译 CloneFactory，再打包后端。`--wait` 等待 MySQL、Redis 和后端均通过健康检查。后端使用未登录的 `GET /api/auth/me` 返回 401 作为启动信号。随后请求 CSRF 接口，预期返回 HTTP 200、JSON 令牌及 `SESSION` Cookie。如果改过 `RBAC_HTTP_PORT`，将命令中的 `8080` 换成实际端口。非 Windows 终端可使用 `curl`。

### 初始化演示数据和测试账号

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

## 3. 启动前端，验证登录和实时撤权

保留 Docker 容器运行。另开一个终端，从仓库根目录执行：

```powershell
cd "c1-框架-rbac0-结构化+面向功能/frontend"
npm ci
npm run dev
```

浏览器打开 `http://127.0.0.1:5173`。如果后端用了非默认端口，例如 `8081`，先在前端终端执行 `$env:RBAC_API_TARGET = 'http://127.0.0.1:8081'`，再启动 Vite。Bash 使用 `export RBAC_API_TARGET=http://127.0.0.1:8081`。Vite 会把浏览器的同源 `/api` 请求转发给后端。

1. 用普通窗口登录 `super-admin`，用无痕窗口登录 `engineering-employee`。密码用初始化演示账号时设置的值。两个普通标签页会共用同一个登录 Cookie。
2. 管理端进入「员工管理」，找到「示例工程员工」并打开「管理角色」。确认已授予「示例工程审批员」，若未授予则先分配。
3. 员工在工程申请系统审批一条待审批记录，预期为「鉴权通过／执行成功」。
4. 管理员撤销该员工的「示例工程审批员」角色。员工保持登录，审批另一条待审批记录，预期为「鉴权拒绝／未执行」，记录仍为待审批。
5. 演示后可重新授予角色，供下一位测试人员继续操作。完整的组织策略和浏览器测试见 [前端运行与验收](../frontend/README.md)。

## 4. 配置 Apifox 并请求真实后端

1. 在 Apifox 桌面版打开「ooad项目」团队的 `OOAD RBAC` → `C1-RBAC0`。若暂时没有团队访问权，或接口列表仍是首批版本，可以在自己的临时项目导入仓库的 [OpenAPI](../api/openapi.yaml)。共享项目由负责人同步。[OpenAPI 导入说明](https://docs.apifox.com/import-openapi-swagger)
2. 在「环境管理」新建或选择本地调试环境，把该模块的「前置 URL」填为 `http://127.0.0.1:8080`。端口以自己的 `RBAC_HTTP_PORT` 为准。发送前在右上角选中这个环境，检查「实际请求」里的 URL。选中本地 Mock 或云端 Mock 时，请求会由 Mock 服务处理。[Apifox 发送请求说明](https://docs.apifox.com/send-api-request)

| 顺序 | 请求 | 需要填写与预期结果 |
| --- | --- | --- |
| 1 | `GET /api/auth/csrf` | 保存匿名 `SESSION` 和 JSON 响应中的 `token` |
| 2 | `POST /api/auth/login` | 带匿名 Cookie 和 `X-CSRF-TOKEN`。Body 选 JSON，例如 `{"username":"engineering-employee","password":"1234"}`。成功后保存返回的新 `SESSION` |
| 3 | `GET /api/auth/me` | 带新 Cookie，确认返回的是当前登录员工 |
| 4 | `GET /api/auth/csrf` | 带登录 Cookie，重新领取写操作使用的令牌 |
| 5 | `GET /api/business-systems/demo-engineering-app/requests` | 带登录 Cookie，找一条 `PENDING` 申请并记下 ID |
| 6 | `POST /api/requests/{requestId}/approve` | 替换申请 ID，带登录 Cookie 和第 4 步令牌。工程员工仍有审批权限时预期返回 200、`GRANTED`、`SUCCEEDED` |

浏览器里若刚做完撤权演示，先恢复员工角色，再测 Apifox 的成功路径。复现授予和撤销时，管理员与员工必须分别保留各自的 Cookie 和 CSRF 令牌。若手工填 `Cookie: SESSION=...`，检查实际请求只发送了当前用例的会话。撤权后用另一条待审批记录验证 `403 PERMISSION_DENIED`。[首批接口验收](../api/首批接口验收.md)

## 5. 检查 MySQL 和关系表

### 直接用 PowerShell 检查

以下命令在「部署运行说明」目录执行，用容器内的客户端检查应用账号、当前数据库和 Flyway 迁移历史。

```powershell
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SELECT DATABASE(), CURRENT_USER(); SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"'
```

预期库名为 `ooad_rbac0`，当前 V1–V5 的 `success` 均为 `1`。若迁移表不存在，先查看后端启动日志。

### 在 Navicat 里查看表

本机运行 Compose 的队友先创建一个 MySQL 连接，名称可使用 `ooad_rbac_c1_local`。

| 字段 | 本地配置 |
| --- | --- |
| 主机 / 端口 | `127.0.0.1` / `3307`，或者实际的 `MYSQL_HOST_PORT` |
| 用户 / 密码 | `rbac` / 自己 `.env` 中的 `RBAC_DB_PASSWORD` |
| 数据库 | `ooad_rbac0` |

在 Navicat 点「连接 → MySQL」，按表格填写并点「测试连接」。连接后展开 `ooad_rbac0` → 「表」。可查看 `flyway_schema_history`、`employee_role`、`role_permission`、`organization_permission` 和 `simulated_request`。在前端或 Apifox 提交授撤权后，刷新对应的表核对变化。

负责人随后会邀请队友加入 Navicat 项目团队。收到邀请后登录 Navicat Cloud 并打开团队项目。共享连接的主机、账号和访问方式以负责人提供的配置为准。共享连接中的 `127.0.0.1` 始终指向当前机器，团队项目成员权限和 MySQL 登录权限分别配置。 [Navicat 连接配置](https://www.navicat.com/manual/online_manual/en/navicat_18/win_manual/con_gen_settings.html)

## 6. 自动化测试和 JaCoCo

### 后端集成测试

手动演示使用 `ooad_rbac0`。自动化测试使用独立 MySQL 容器里的 `ooad_rbac0_test` 和独立 Redis，测试库无需初始化演示账号。以下从 C1 的 `backend` 目录执行，要求本机已有 JDK 21 和 Maven 3.9。示例端口 `33326` 和 `36389` 需空闲。

```powershell
$env:MYSQL_ROOT_PASSWORD = [Guid]::NewGuid().ToString('N')
$env:MYSQL_PASSWORD = [Guid]::NewGuid().ToString('N')
docker run -d --name c1-jacoco-mysql -p 127.0.0.1:33326:3306 -e MYSQL_ROOT_PASSWORD -e MYSQL_PASSWORD -e MYSQL_DATABASE=ooad_rbac0_test -e MYSQL_USER=rbac_test mysql:8.4.7
docker run -d --name c1-jacoco-redis -p 127.0.0.1:36389:6379 redis:7.4.6-alpine
docker exec c1-jacoco-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" "$MYSQL_DATABASE" -Nse "SELECT 1"'
docker exec c1-jacoco-redis redis-cli ping
```

等 MySQL 查询返回 `1`、Redis 返回 `PONG`，在同一终端继续：

```powershell
$env:RBAC_DB_URL = 'jdbc:mysql://127.0.0.1:33326/ooad_rbac0_test'
$env:RBAC_DB_USER = 'rbac_test'
$env:RBAC_DB_PASSWORD = $env:MYSQL_PASSWORD
$env:RBAC_REDIS_HOST = '127.0.0.1'
$env:RBAC_REDIS_PORT = '36389'
$env:RBAC_SESSION_NAMESPACE = 'rbac0:jacoco:' + [Guid]::NewGuid().ToString('N')
mvn -f tools/clonefactory/pom.xml install
mvn verify
```

检查 Maven 输出的 `Failures`、`Errors`、`Skipped`，三项都应为 0。打开 `backend/target/site/jacoco/index.html` 查看行和分支覆盖率。测试完成后可用 `docker stop c1-jacoco-mysql c1-jacoco-redis` 停止这两个容器。同名容器已存在时，可沿用第一次设置的账号和密码，加上 `docker start` 复用，或为新一轮测试换一组容器名及空闲端口。

### 前端构建和浏览器测试

在 `frontend` 目录运行 `npm run build` 验证 TypeScript 和生产构建。Playwright 使用已初始化、可修改的隔离演示环境。先按 [前端测试说明](../frontend/README.md#验证) 安装 Chromium 和配置演示密码。若 Vite 已占用 `5173`，可设置 `$env:RBAC_FRONTEND_TEST_PORT = '5174'`，然后执行 `npm run test:e2e`。测试会调整授权关系和审批记录。

## 7. 远程主机和常用命令

Linux 主机也可在同一目录运行上述 Compose 命令。若从自己电脑调试远程实例，保持端口绑定在远端回环地址，在自己电脑发起 SSH 转发，目标主机及用户换成实际配置。

```sh
ssh -N -L 18080:127.0.0.1:8080 -L 13307:127.0.0.1:3307 user@your-host
```

保持 SSH 会话开启。Apifox 使用 `http://127.0.0.1:18080`，Navicat 使用 `127.0.0.1:13307`。如果在远程主机改过 Compose 宿主机端口，对应修改 SSH 命令右端端口。当前配置用于 C1 单机运行，C2 的 Docker Swarm 文件随 C2 交付。

| 操作 | 命令或检查 |
| --- | --- |
| 更新后端 | `git pull --ff-only` 后运行 `docker compose up -d --build --wait --wait-timeout 120` |
| 查看错误 | `docker compose ps`，`docker compose logs --tail=100 backend mysql redis` |
| 停止并保留数据 | `docker compose down`。MySQL 与 Redis 使用命名卷，之后 `up` 可复用。 |
| 服务起不来 | 确认 Docker 正在运行、端口未占用、`.env` 已填好。已有 MySQL 数据卷的用户和密码以数据库内当前配置为准，修改 `.env` 不会自动修改已有数据库账号。 |
| 基础镜像拉取超时 | 检查 Docker 网络，或在 `.env` 启用 `.env.example` 中验证过的 Amazon ECR Public Maven 与 Temurin 同版本镜像。 |
| Apifox 请求失败 | 先检查「实际请求」的前置 URL 和端口，再查看错误码。`CSRF_INVALID` 时使用当前 Cookie 重新领取令牌，`PERMISSION_DENIED` 时核对实际授权。 |
| Navicat 无法连接 | `Can't connect` 时用 `docker compose ps mysql` 确认容器和端口。`Access denied` 时核对用户名 `rbac` 与数据库当前密码。 |
| Maven 集成测试被跳过 | 检查测试库名必须是 `ooad_rbac0_test`，并设置 `RBAC_REDIS_PORT`。运行前确认 MySQL 和 Redis 都已就绪。 |

完整部署与双端回归结果见 [C1 本地验收记录](../docs/本地验收记录.md)。
