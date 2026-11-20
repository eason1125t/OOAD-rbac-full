# C2 测试与验收

详细的操作、预期、实测结果、两版差异与脚本运行说明见 [双版本测试记录说明](./双版本测试记录说明.md)。

本目录集中维护两套 RBAC1 的测试源码、运行入口、需求追踪及本次真实执行证据。生产代码仍分别在 backend-func、backend-oo；两版独立 Maven 构建，共用一份 HTTP 契约测试。

## 目录与组织

- `后端/common/java`：35 项共同 HTTP 契约，包括会话、权限、组织策略、申请、并发和日志。版本、Cookie 和 Redis 命名空间由各版 Maven 参数注入；类启动条件严格限定对应 `_test` 数据库。
- `后端/func/java`、`后端/oo/java`：各版图规则、初始化、工作簿解析；OO 另有纯领域状态及三端口架构检查。保留 C1 回归编号，新增用例使用 C2 编号。
- `前端/tests`：两版共用浏览器用例及 Excel 摘要核对，含双标签页隔离。该目录有独立 package.json/锁文件，避免迁出 frontend 后无法解析 Playwright。
- `脚本/run_backend.py`：本机真实依赖执行器，保留环境、版本、源码摘要、退出码和测试/覆盖率统计；不输出数据库密码。
- `结果/func`、`结果/oo`：完整命令 TXT、输出 TXT、Surefire XML、原始 JaCoCo HTML/XML、结果统计及实际 HTML 截图。
- `历史/原对象版AuthSessionTest.java.txt`：抽取共同契约前的原文，仅供核对，不参与编译。此前双版本测试记录也已移入本目录的 `历史/双版本测试记录.md`。旧前端转发配置保存为 `历史/原前端Playwright入口.ts.txt`，仅作迁移记录，不参与执行。

迁移逐个移动明确文件，未批量删除目录。原 `src/test` 和 `frontend/tests` 空目录可暂留，不是第二套执行入口。两个 Maven POM 用 testSourceDirectory 接本版源码，build-helper 接公共源码；原浏览器 npm 脚本转发到本目录，不再保留 frontend/playwright.config.ts 文件。后端 pom.xml 和前端 package.json 仍位于所属工程，负责接入测试；target 与 node_modules 是自动生成物，不作为另一套人工维护的测试文件。

## 环境与运行

Java 21、Maven 3.9、Node 24（或满足项目 engines）、Python 3、Edge、MySQL 8.4 和 Redis 7.4。本机 C2 Docker 容器对应端口为 MySQL 33327、Redis 36390，测试库分别为 `ooad_rbac1_func_test`、`ooad_rbac1_oo_test`。脚本仅在子进程环境使用现有容器凭据。其他机器可手工设置 RBAC_DB_URL、RBAC_DB_USER、RBAC_DB_PASSWORD、RBAC_REDIS_HOST、RBAC_REDIS_PORT，按各版 POM 执行 verify。

在 C2 根目录运行：

```powershell
python ./测试/脚本/run_backend.py func
python ./测试/脚本/run_backend.py oo
```

数据库不符时集成测试不会执行，归档脚本将任何跳过视为非完整验收。`verify` 生成覆盖率报告；JaCoCo agent 的 append=false 防止旧执行数据混入。测试库与浏览器演示库分开，不能把 runner 指向真实业务库。

浏览器前先分别启动最新两版 JAR，使用 `_review` 演示库、18181/18182 端口和各版 Cookie。准备八个演示账号，保留组织策略及继承配置；三份 Excel 导入步骤沿用阶段运行说明。首次安装依赖及执行：

```powershell
npm --prefix ./测试/前端 install
npm --prefix ./frontend run build
$env:RBAC_BROWSER_PREVIEW = 'true'
$env:RBAC_BROWSER_CHANNEL = 'msedge'
$env:RBAC_EXCEL_REVIEW_DIR = '<课程原表目录>'
npm --prefix ./测试/前端 test -- --timeout=90000
node ./测试/前端/report-images.mjs
```

原表变量缺失会跳过 Excel 验收，不可称全量通过。浏览器测试会创建申请、改角色和组织策略，使用专门演示库。原始工作簿继续忽略，不写入测试报告；全量核对只保存行数和字段摘要。

## 重新整理与补充内容

两版 35 项 HTTP 原文仅版本配置不同，本轮合为一份共同输入与断言。保留已有有效用例，并补充 C2-01/02 菱形图的闭包去重及孤立分支、C2-07 逆向影响覆盖全部上游的独立预期，两版同样执行。预期集合手工给定，不调用被测逻辑生成期望值。

这是已有行为的测试重组与补充，未改生产业务代码，没有伪造新的 TDD RED；新测试若发现真实缺陷，再按 RED→修复→GREEN 处理。此前开发阶段的失败见历史记录，不能把迁移路径错误当作功能失败证据。

## 报告阅读与限制

[需求追踪](./需求追踪.md) 关联 C2-01 至 C2-16。[本次执行报告](./执行报告.md) 记录实际通过/失败/跳过及覆盖率。结果目录的 JaCoCo 图片是归档 HTML 的真实截图，原始 HTML/XML 可复核。源码摘要对应本次工作区，Git HEAD 单独记录，不把未提交工作区误称已提交版本。

JaCoCo 分母包含实际生产类和编译生成代码，没有通过新增排除项美化指标。分支与行覆盖率含义不同，课程资料未规定最低百分比。本次本机回归不代替 Swarm 集群验收；[GRASP 专项](../grasp与项目.md) 已完成；[总结与答辩稿](../项目总结.md) 已形成，[部署交接说明](../部署交接文档.md) 已明确测试与集群验收分工；实际部署和终版回填仍按收尾清单推进。三模型比较已另行完成，独立小样的 10 项测试及命令见 [模型比较测试](./模型比较/README.md)，不计入本报告的正式后端统计。


## 本机 Maven PATH

本机已把现有 Maven 3.9.11 的 bin 加入当前用户 PATH，PowerShell 与 CMD 的 mvn -v 均验证正常。修改前已打开的终端需要关闭后重新打开；若在 IDE 内使用终端，应重新启动该宿主应用。直接执行 verify 仍需正确的 MySQL/Redis 环境变量，完整归档建议继续使用本目录的 run_backend.py。


## 测试交付与集群部署

测试源码、说明与历史报告随仓库交付，不作为常驻 Swarm 服务，不进入生产 JAR。本地/CI 可运行完整测试与 JaCoCo；集群部署后仍需通过真实 /func/、/oo/ 入口验证功能、会话、持久化和服务器 Logback。

现有 Maven HTTP 测试会启动本地应用并强制选择 `_test` 库，run_backend.py 读取约定的本机容器；现有 Playwright 默认启动本地 Vite。它们都不是可直接指向远程演示库的验收工具。新增 [外部 HTTP 检查](./部署/smoke.py) 和 [外部 Playwright 配置](./前端/playwright.remote.config.ts)，直接访问指定站点，不启动 Vite；后者复用 3 项入口/隔离测试。使用方法、实测批次与局限见 [部署验证](./部署/README.md)，不等于原 23 项已全部转为远程模式。不得以本地报告代替服务器记录，详见 [部署交接第 9 节](../部署交接文档.md#9-测试代码是否一起部署)。
