# C2 测试说明、结果与部署后验收

更新：2026-11-22。本文统一维护测试范围、运行步骤、实际结果和后续验收。阶段说明移入测试/归档，原始JaCoCo、TXT、JSON、HTML和截图保留在测试/结果。[项目总结](../docs/项目总结.md)讲实现，[部署说明](../部署/README.md)讲发布目标与操作。

## 1. 当前实际执行到哪里

| 范围 | 实际结果 | 证据 |
| --- | --- | --- |
| JWT完整func后端 | 194通过，0失败/错误/跳过；行1717/1790=95.92%，分支642/771=83.27% | [结果](结果/JWT前端与整体验收-20261121/后端-func/结果.json)、[JaCoCo](结果/JWT前端与整体验收-20261121/后端-func/jacoco/index.html) |
| JWT完整oo后端 | 198通过，0失败/错误/跳过；行1744/1816=96.04%，分支645/775=83.23% | [结果](结果/JWT前端与整体验收-20261121/后端-oo/结果.json)、[JaCoCo](结果/JWT前端与整体验收-20261121/后端-oo/jacoco/index.html) |
| JWT完整Edge浏览器 | 51通过，0失败/跳过/重试通过；含原表全量摘要、JWT和双版场景 | [结果](结果/JWT前端与整体验收-20261121/最终完整验收/结果.json)、[HTML](结果/JWT前端与整体验收-20261121/最终完整验收/browser-report/index.html) |
| JWT容器交付 | 脚本7项、五容器链路、HTTP7组、外部浏览器3项通过 | [容器](结果/JWT前端与整体验收-20261121/容器交付/结果.json)、[HTTP](结果/JWT前端与整体验收-20261121/容器交付/HTTP冒烟结果.json) |
| 本机实际换包 | 原review库保留；网关及只读浏览器3项通过 | [数据/运行核对](结果/JWT本机切换-20261122/切换后数据与运行核对.json)、[浏览器](结果/JWT本机切换-20261122/浏览器验收/browser-results.json) |
| 接口和图件 | OpenAPI2.5.0、49操作/44模型、Apifox回读；49组图件、绘图工具9项 | [同步](结果/JWT交付同步-20261122/Apifox同步结果.json)、[图件](../docs/design/README.md) |
| 模型小样 | 独立10项通过，不混入后端统计 | [输出](模型比较/GREEN.txt) |
| 课程Swarm | **待队友部署后实测** | 按第4节执行并回填 |

48/52、88/92后端及23/35浏览器是早期Session批次，不叠加为当前总数。Session通过不当作JWT通过，本机Compose不当作Swarm，静态检查不当作HTTP，客户端故障桩不代替后端真实到期和Redis并发验证。

本次文档收尾只改说明、引用和交付检查范围，业务/前端运行代码不变；194/198/51引用原实际批次，不宣称本轮重新运行。文档检查和发布核对另存测试/结果/文档收尾-20261122。

## 2. 测什么，代码在哪里

| 对象 | 重点覆盖 | 位置 |
| --- | --- | --- |
| 身份/JWT | 账号/员工资格、签名/算法/iss/aud、过期、sid、退出、15分钟/8小时、严格轮换/重用/并发 | 后端/common/java/authentication及contract |
| 八项能力 | me兼容、角色来源、单权限、API映射/未知API、部门层级/hasChildren、搜索分页、越权和管理员详情 | 共同HTTP、[静态契约](契约/check_identity_contract.py) |
| 角色/组织 | 直接/继承/多路径、撤角色/删边、停用/重开/同源重授、跨系统同码、并发防环 | 后端/common、func、oo |
| 原有业务 | 登录/工作区、查看与动作权限、本人和状态、并发、迁移/初始化 | 两版业务及契约测试 |
| 前端 | Bearer/内存、恢复、Worker并发、同版标签页/双版、按钮/目录、Excel摘要 | 前端/tests；本机只读子集在前端/本机切换 |
| 部署/设计 | 凭据保留、密钥校验、网关/深链/日志、图件来源 | 部署/test_manage.py、部署/smoke.py、图件/test_diagram_checks.py |

普通用户不能越过自身范围，管理员也受管理范围约束；ID-08仍仅管理员，自身资料用me。400/401/403/404/409/503分别验证，服务故障不等于allowed=false。

## 3. 本机或CI怎样复验

### 3.1 环境

准备Java21、Maven、满足frontend engines的Node、Python3、OpenSSL、独立测试MySQL/Redis和浏览器，从C2执行：

```powershell
mvn -f backend-func/tools/clonefactory/pom.xml install
npm --prefix frontend ci
npm --prefix 测试/前端 ci
```

现有运行器针对本机ooad-rbac-c2-dev-mysql（33327）和Redis（36390），密码仅进子进程环境；不是任意服务器通用命令。异地先准备独立依赖或修改连接适配，禁止把测试库替换成演示/现有业务库。测试密钥每批在工作区外临时生成，不用部署私钥。静态契约另需PyYAML、jsonschema、openapi-schema-validator，可在自己的Python虚拟环境安装。

### 3.2 完整后端

每批选新输出目录；隔离构建不覆盖当前运行target/jwt-final：

```powershell
python 测试/脚本/run_backend.py func --output-dir 测试/结果/复验-新批次/func --build-dir target/handoff-check
python 测试/脚本/run_backend.py oo --output-dir 测试/结果/复验-新批次/oo --build-dir target/handoff-check
```

运行器只连接专用ooad_rbac1_func_test/oo_test，归档命令、源码摘要、Surefire、JaCoCo和结果图。--tests是定向，不写成完整通过；失败、跳过或零测试如实记录，不能用旧jacoco.exec冒充新覆盖率。不执行mvn clean破坏运行目录。

### 3.3 静态、工具与前端构建

```powershell
python 测试/契约/check_identity_contract.py
python 测试/部署/test_manage.py -v
python 测试/图件/test_diagram_checks.py
npm --prefix frontend run build
python 测试/脚本/check_jwt_delivery.py --evidence-dir 测试/结果/JWT前端与整体验收-20261121 --output-dir 测试/结果/静态复核-新批次
```

最后一条要求已有target/jwt-final和对应源码验收记录。新检出机器没有JAR，应先构建并实际验收，再使用自己的证据目录；该脚本只核对产物/源码/说明，不新增真实业务通过结论。

### 3.4 完整浏览器与独立容器

```powershell
python 测试/脚本/run_frontend_identity.py --output-dir 测试/结果/浏览器复验-新批次 --suite full --backend-build-dir target/jwt-final --excel-dir ../excel
python 测试/脚本/run_jwt_deployment.py --output-dir 测试/结果/容器复验-新批次
```

完整浏览器需要两版JWT JAR、私下提供的原表、Edge和本机上述依赖；运行器创建final_test批次库、临时28181/28182/15173，会写独立演示数据，结束停止自己创建的进程。不能对当前review库做全量写入测试。

容器运行器需Docker和Edge，从源码构建三镜像，在未占用18081及专用Compose卷验证HTTP/页面；结束仅停止该批容器，卷与证据保留，不触碰课程Swarm。资源清理按明确对象执行，不全局prune。

## 4. 部署后如何测试

顺序为：环境/只读入口 → 基础HTTP → 合成写入 → 浏览器 → 权限边界 → 持久化/恢复 → 回填。使用真实集群入口，新建测试/结果/Swarm-日期-批次，记录执行人、时间、Git SHA、镜像摘要与环境。示例URL必须换成实际站点。

### 4.1 服务与基础入口

对照部署说明检查五服务副本、节点、卷、稳定密钥和两库V1—V9；访问首页、两版登录及深链刷新，未登录me应401，未知API应JSON而非HTML。HTTPS检查Secure/HttpOnly/Path、Worker加载及JWT未进入WebStorage，数据库/Redis不公开。

```powershell
python 测试/部署/smoke.py --url https://实际课程入口 --output 测试/结果/Swarm-新批次/HTTP-基础.json
```

默认不写申请，但会登录/刷新/退出自己的记录。默认super-admin/1234演示账号可用RBAC_SMOKE_USERNAME、RBAC_SMOKE_PASSWORD覆盖，真实密码不放命令或报告。父输出目录先建立。

### 4.2 合成写入与真实浏览器

```powershell
python 测试/部署/smoke.py --url https://实际课程入口 --exercise --output 测试/结果/Swarm-新批次/HTTP-写入.json
$env:RBAC_DEPLOY_BASE_URL = 'https://实际课程入口'
$env:RBAC_BROWSER_CHANNEL = 'msedge'
$env:RBAC_BROWSER_RESULTS_DIR = (Join-Path (Get-Location) '测试/结果/Swarm-新批次/浏览器')
npm --prefix 测试/前端 run test:remote
```

--exercise每版新增、修改并撤回一条合成申请；远程浏览器3项验证两版入口及双标签页/跨版隔离，其中会新增并审批合成申请。只能在约定演示数据上运行，记下遗留申请ID。浏览器使用既有演示账号，HTTP账号变量不会自动替换浏览器账号。Linux/CI使用已安装Playwright Chromium时不设置msedge。远程配置不启动Vite，3项不等于完整51项。

### 4.3 八项能力与业务边界

| 场景 | 实际操作和预期 |
| --- | --- |
| 登录/工作区 | 两版登录、选系统、页面恢复；坏账号/停用员工拒绝，不能用传入employeeId冒充身份 |
| me/角色来源 | 无systemCode兼容；指定系统返回直接/继承来源及策略后权限；自身查询不需管理员资格 |
| 单权限/API | request:*一致、已知无权false、未知系统/API404、多个要求ALL；真实操作仍检查记录及状态 |
| 目录/隔离 | tree根/直接子级、hasChildren、q、分页计数；普通最小字段/启用员工；管理员范围；ID-08普通人403 |
| 角色与继承 | 直接授予/撤销、继承增删、替代路径；沿用原登录下一请求按当前关系判定 |
| 组织策略 | 停用拒绝、重开后同源重授；不以不合格替代来源绕过，测试后恢复演示设置 |
| JWT生命周期 | 坏签名/受众401、真实到期/8小时、严格轮换、受控旧凭证重用只撤该sid、其他登录保留 |
| 页面/标签页 | 同版协调刷新/退出、跨版不受影响、切账号清旧数据、断网不重放不确定写入 |
| 申请回归 | 查看/动作权分开，发起/改名/撤回/审批/驳回/本人删除及并发终态正确 |
| 原表与字段 | 三表分页摘要一致；不输出原始员工行、原电话、哈希或凭证 |

令牌重用在受控测试客户端/隔离环境验证，不归档原始凭证。时钟、Redis故障和并发可在独立环境复验；未在集群执行就标待验证，不能把客户端桩当后端实际到期。

### 4.4 日志、重启、持久化与恢复

HTTP的requestId与两版Logback的version/action/outcome对应；提交后才记成功，拒绝不出现成功事件。保留脱敏日志TXT。

维护窗口滚动重启后端检查密钥与登录，重启Redis验证AOF/撤销状态，重启MySQL核对业务数据/迁移历史。节点失效单列，不以同机重启代替多节点调度。按部署说明备份后在隔离库实际恢复，核对两库/授权/申请/Flyway；只有备份文件不能写“恢复通过”。

最后在Apifox课程真实环境重放代表请求，核对Bearer、刷新Cookie与CSRF；保留原环境/用例，不导入原始凭据。跨项目受众、公钥和在线撤销仍是后续系统联调。

## 5. 怎样记录，怎样算完成

每批保留命令TXT、控制台TXT、环境/提交/镜像、JSON或HTML；后端全量另存JaCoCo HTML/XML与结果图。截图只留必要内容，不保留认证网络trace，不发布原表行、私钥、Cookie、密码或JWT。失败批次保留并链接修正，不能覆盖成全通过。上传前已将两份历史失败HTTP输出中的访问JWT脱敏，断言和计数保留，见[脱敏记录](结果/文档收尾-20261122/历史输出脱敏记录.json)。

| 集群验收项 | 当前结果 | 必须补充 |
| --- | --- | --- |
| 五服务/网关/TLS/迁移/应用账号隔离 | 待验证 | 节点、状态、迁移和网络配置 |
| 基础HTTP及合成申请写入 | 待验证 | 两份HTTP JSON、TXT |
| 浏览器3项及八项能力边界 | 待验证 | 报告、截图、逐项记录 |
| 继承/撤权/组织重授/目录隔离 | 待验证 | 原登录前后允许/拒绝证据 |
| 日志/重启/持久化 | 待验证 | requestId和重启前后记录 |
| 隔离恢复/多节点/Apifox课程环境 | 待验证 | 恢复结果与真实入口请求 |

实际执行写通过/失败/数量，未执行写待验证和负责人，不能因本机通过自动勾选。完成后回填部署说明交接表，在本文追加实际批次及未解决事项。

## 6. 历史说明和结果

测试/归档仅保留阶段说明，不再分别维护当前命令；测试/结果保留Session、JWT和失败修正原始证据。日期早不等于无用。构建缓存可再生，两版正在使用的target/jwt-final和结果归档不能整体删除。清理记录和本机个人配置不作为队友部署依赖。
