# C2 部署说明与队友交接

更新：2026-11-22。本文是部署操作与交接的唯一维护入口。[项目总结](../docs/项目总结.md)解释实现，[测试说明](../测试/README.md)集中部署后验收。命令均从C2根目录执行。课程Swarm仍待队友实际部署，本机Compose通过不能替代集群结果。

## 1. 最终要达到什么结果

| 目标 | 完成时应观察到的结果 |
| --- | --- |
| 一个真实站点 | 首页、/func/、/oo/可从课程入口访问，深链刷新正常，API错误不回退成HTML |
| 五项Swarm服务 | frontend、backend-func、backend-oo、mysql、redis都在课程集群，数据库不依赖开发者电脑 |
| 两版可对照且隔离 | 共用前端、独立库和身份配置；可分别登录，退出一版不影响另一版 |
| 功能与主契约一致 | OpenAPI2.5.0、49操作/44模型；八项身份能力、继承、策略与申请可实际演示 |
| 持久化和凭据明确 | 两库Flyway V1—V9、Redis AOF、约定数据节点/卷和稳定密钥；重启后符合数据约定 |
| 入口配置正确 | 生产HTTPS和Secure Cookie，正确公开Path；只发布前端业务端口，数据库/Redis不公开 |
| 可查可恢复 | 两版Logback对应实际申请ID；备份、隔离恢复、镜像摘要和验收记录齐全 |

初始单副本方案不代表数据库高可用。内存小样、测试运行器、Maven、Apifox、Navicat和UML工具不作为常驻服务。

请求路径：浏览器/Apifox → HTTPS/前端Nginx → /func或/oo对应后端 → 本版MySQL库＋共享Redis。网关去除前缀，透传Authorization和Cookie；Vite开发代理不会随dist部署。

## 2. 部署前准备与交接

| 项目 | 队友准备/填写 |
| --- | --- |
| 版本 | 本次“C2收尾（已对接api契约）”的Git SHA；所有节点使用同次构建 |
| 集群 | manager、节点架构、唯一数据节点hostname、调度和磁盘条件 |
| 入口 | 实际URL、端口、域名/TLS位置；HTTPS时RBAC_AUTH_COOKIE_SECURE=true |
| 镜像 | 所有节点可拉取的仓库、三应用镜像标签及摘要、仓库权限 |
| 数据 | 空卷初始化或已有库迁移；三份原始Excel私下交接 |
| 凭据 | MySQL/Redis凭据、func/oo稳定RSA密钥、访问限制及私有备份 |
| Apifox | OOAD RBAC项目9046536、C2模块8918000访问权限和课程站点环境 |
| 回退 | 原镜像/配置、数据备份、负责人、维护窗口及恢复方案 |

未知参数标“待填”，不能用localhost或示例域名代替部署地址。Git不包含原表、私钥、真实密码、SQL、本机卷、target或node_modules；按源码/锁文件重建。个人答辩草稿继续本地保留。

## 3. 文件和运行条件

| 文件 | 职责 |
| --- | --- |
| [Dockerfile.backend](Dockerfile.backend) | Java21多阶段构建，先安装CloneFactory，非root运行 |
| [Dockerfile.frontend](Dockerfile.frontend)、[nginx.conf](nginx.conf) | 一份前端、两路API、深链、Worker与错误路由 |
| [stack.yaml](stack.yaml) | 五服务、overlay、file secret、健康检查、数据节点、日志轮转 |
| [manage.py](manage.py) | 构建/可选推镜像、凭据校验、渲染和一次性初始化 |
| [mysql-init.sh](mysql-init.sh)、[redis-start.sh](redis-start.sh) | 空卷建两库/用户、读取Redis凭据和启用AOF |
| [backend-entrypoint.sh](backend-entrypoint.sh)、[mysql-backup.sh](mysql-backup.sh) | 文件凭据读取、明确容器目标的两库备份 |
| [local.ps1](local.ps1) | 本机Windows开发服务管理，不操作课程集群 |

准备机需Git、Docker、Python3、OpenSSL和镜像仓库权限；Swarm由负责人预先准备。基线Java21、Maven3.9、Node24、MySQL8.4.7、Redis7.4.6；本机Node需满足frontend/package.json engines。Docker上下文必须为整个C2，不能只传backend目录。Linux入口脚本保持LF。

## 4. 课程Swarm操作步骤

### 4.1 检出同一提交，配置并构建镜像

首次把部署/.env.example复制为部署/.env；已有文件直接编辑，不覆盖。填写实际仓库镜像、RBAC_DATA_NODE、RBAC_HTTP_PORT、RBAC_STACK_NAME和Secure设置。通过docker node ls核对hostname/架构，先登录镜像仓库：

```powershell
python 部署/manage.py build --push
```

默认不加--push只本机构建；stack deploy不负责build。示例镜像名不等于队友可拉取的仓库。基础镜像可按.env.example的RBAC_*_IMAGE指定可信源，保存实际摘要与架构。

### 4.2 在真实manager准备凭据、渲染、发布

私有仓库也先在manager登录；在manager源码目录运行：

```bash
python3 部署/manage.py secrets --create-in-swarm
python3 部署/manage.py render
docker stack deploy --with-registry-auth -c 部署/stack.rendered.yaml c2
docker stack services c2
docker stack ps c2 --no-trunc
```

默认stack名c2，改RBAC_STACK_NAME后命令同步改名。渲染含本机路径，应在真实manager重新生成，不能直接搬用Windows渲染文件。脚本不执行swarm init，也不修改其他项目。

首次生成四个随机数据库/Redis凭据和两版各一对RSA2048公私钥，共八个file secret。已有文件保留并校验，不每次生成；同版副本共用同一密钥，两版独立。已有Swarm同名secret不自动轮换，新机器生成的本地文件不是原secret的备份。私钥不进入环境值、镜像层、日志或Git。

认证使用RBAC_AUTH_*、RBAC_JWT_*；旧RBAC_SESSION_COOKIE_SECURE必须替换为RBAC_AUTH_COOKIE_SECURE，脚本会拒绝旧键。HTTPS设true，公开Cookie Path为/func/api/auth/、/oo/api/auth/。Spring Session已移除，但Redis仍保存在线sid，必须保留。

### 4.3 初始化或迁移

确认MySQL/Redis健康、后端迁移成功后再选择数据方案。空环境可分开运行：

```bash
python3 部署/manage.py init --mode demo
python3 部署/manage.py init --mode excel --excel-dir /srv/c2-input
```

原表目录为数据节点真实绝对路径，以只读挂载提供；默认两版，也可--edition func/oo。确认任务退出码0，失败/超时保留供查日志。初始化参数不能留在常驻服务命令中。

已有库先备份、核对原凭据/两库/flyway_schema_history。mysql-init仅空卷运行；不能用重新生成密码或删卷修复。JWT无新数据库迁移，V9是API映射两表。演示初始化保留已变更的继承/授权/组织策略，不用作测试重置。默认演示密码只用于约定课程演示，不用于真实业务。

### 4.4 检查服务和日志

核对五服务期望副本、无反复重启、卷和数据节点、两库V1—V9及应用账号跨库拒绝。MySQL/Redis固定数据节点，本地卷不会自动在其他节点恢复。

```bash
docker service logs --timestamps c2_backend-func
docker service logs --timestamps c2_backend-oo
```

健康检查不替代业务测试。申请审计按version/action/requestId/outcome对应事务最终结果，不记录密码、Authorization、Cookie或刷新凭证。

## 5. 部署后还必须做什么

1. 按[测试说明第4节](../测试/README.md)，依次验证真实入口、HTTP、合成数据写入、浏览器、八项身份和原业务边界。
2. 改授权/重启等场景安排维护窗口，避免与队友演示互相影响；测试后记录并恢复演示设置。
3. 核对HTTPS、Secure、网关、Worker、同版密钥一致和时间同步；不为调试直接全开CORS。
4. 验证MySQL/Redis重启持久化，对SQL备份做隔离恢复演练；节点故障和迁移另行验证。
5. 配置Apifox课程真实站点环境，执行代表请求；保留已有C1/C2、环境和用例，已同步主契约不用重复导入替代测试。
6. 回填下表；命令TXT、报告、日志和截图放测试/结果的新Swarm批次，更新测试说明的实际结果。

| 最终回填项 | 当前状态 |
| --- | --- |
| Git SHA、镜像标签/摘要、节点架构 | 待队友填写 |
| URL、TLS/Secure、执行人和时间 | 待队友填写 |
| 五服务、两库迁移、账号隔离 | 待队友实测 |
| 外部HTTP、浏览器、八项身份/业务 | 待队友实测并链接报告 |
| Logback、持久化、备份恢复 | 待队友实测并链接证据 |
| 未通过/未执行项、负责人和后续安排 | 待队友填写 |

完成这些才能从“本机交付”更新为“课程部署验收完成”。已对接API契约不等于其他项目已完成系统联调。

## 6. 更新、备份和回退

更新前保存旧Git SHA、镜像摘要、配置和secret备份；数据节点明确本项目MySQL容器ID后运行：

```bash
sh 部署/mysql-backup.sh 明确的mysql容器ID /安全目录/c2-更新前.sql
```

退出码0并经隔离恢复验证后才能依赖；SQL不上传Git。恢复前和负责人确认范围，停止两版写入，以匹配凭据导入并核对两库、Flyway历史和业务数据。

更新镜像后重新构建/推送、render和stack deploy。服务回退不会逆转数据库迁移，需核对旧应用兼容性。修改mysql-init/redis-start配置时按管理工具更新RBAC_CONFIG_VERSION；这是Swarm配置发布标识，不用于用户或令牌撤销。凭据旋转单独安排，不自动轮换。

## 7. 本机开发与容器复验

当前Windows本机已切JWT，PowerShell7执行：

```powershell
./部署/local.ps1 -Action Status
./部署/local.ps1 -Action Start
./部署/local.ps1 -Action Stop
```

后端18181/18182、前端5173；-Edition func/oo只管单版，-Preview用28181/28182且不管理前端。脚本核对工作目录、入口、创建时间和PID，不结束未知占用端口的进程。

私有配置为%LOCALAPPDATA%/OOAD-RBAC/c2-jwt/func.json、oo.json，环境变量名到字符串的JSON，至少包含各自RBAC_DB_URL/USER/PASSWORD、RBAC_REDIS_HOST/PORT及RBAC_JWT_PRIVATE_KEY_LOCATION/PUBLIC_KEY_LOCATION，其他认证参数保持分版隔离。私有目录另存稳定密钥、进程记录、日志和原库备份，不随Git交接；新机器自备配置与密钥。

现有运行JAR在两版target/jwt-final。新检出机器先安装CloneFactory，再分别执行`mvn -f backend-func/pom.xml -Dmaven.test.skip=true -Drbac.build.directory=target/jwt-final package`及对象版同等命令；前端执行npm ci。此打包跳过测试，不算验收通过。之后配置私有环境运行local.ps1，不能复制个人凭据。

独立容器复验命令集中在测试说明；它新建专用Compose项目、构建同一源码，结束仅停止该批容器，保留卷与证据，不操作课程Swarm。旧验收容器已清理，8个测试卷按用户决定保留。历史说明放部署/归档，不再另行维护当前步骤。
