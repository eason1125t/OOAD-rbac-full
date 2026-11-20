# C2 StarUML 图件与职责说明

本目录对应 2026-11-19 的本地 C2 实现和共同 OpenAPI 2.1.0。`C2-RBAC1.mdj` 是可编辑的 StarUML 原生模型，包含 `backend-func`、`backend-oo` 核心图和已实现内容补充图三组，共 **42 张**。原有 16 张复核后保留名称，新增 26 张。图中的类和方法来自当前代码，不代表整个项目已经改造为完整 DDD。本目录原有 42 张图保持原范围；三模型比较现已独立交付，复用其中四张并新增满血小样两张，见 [三模型比较图件](../../三模型比较/图件/README.md)。

## 打开与阅读

用 StarUML 打开 [C2-RBAC1.mdj](./C2-RBAC1.mdj)，展开 Model Explorer 的三组模型，双击 Diagram。无需额外扩展。按 [图件索引](./图件索引.md) 选择 PNG 或 SVG 预览，按同名主题对照两版；[diagram-index.json](./diagram-index.json) 提供机器可读索引。原生 UML 类、接口、关系、消息、执行条、活动与状态及逻辑 ER 均可编辑；陈氏 ER 使用 StarUML 原生矩形、菱形、椭圆与线，不是嵌入的整张图片。

| 两版各自的图 | 讲解重点 |
| --- | --- |
| 后端包图 × 1 | 源码中的核心包及直接 import 依赖；OO 新增的 port/domain/infrastructure 子包 |
| 新增继承类图、时序图 × 2 | 范围、写锁、幂等、自继承与环路拒绝；静态图计算与领域对象的职责差别 |
| 员工有效权限类图、时序图 × 2 | 管理可见范围、系统准入、继承来源去重、组织停用和重开、分页 |
| 审批类图、时序图 × 2 | 请求行锁、查看权与审批权、状态规则、持久化、CloneFactory 与审计时机 |
| 同会话实时撤权时序图 × 1 | 员工先审批成功、管理员删边并提交、原员工会话下一次审批拒绝 |

| 补充图号 | 已完成内容 |
| --- | --- |
| 17–19 | 继承陈氏 ER、角色继承逻辑 ER、业务申请逻辑 ER；详见 [数据库逻辑说明](./数据库逻辑设计.md) |
| 20–24 | func 功能模块、OO 领域与基础设施依赖；继承、实时授权和申请 CRUD 活动流程 |
| 25–29 | 申请生命周期状态图；两版组织停用、重开、重授类图与时序图 |
| 30–34 | 两版发起修改、撤回驳回删除时序；双标签页登录退出协议时序 |
| 35–40 | 前端组件与 API 路由、申请操作、Excel 浏览、角色权限页；事务提交与审计时序；登录工作区类图 |
| 41–42 | 申请并发状态竞争、多路径权限及删除不同继承路径的活动图 |

图只显示所选用例的主要协作。新增继承图明确幂等、自继承和成环的终止路径；有效权限图展开核心判定；审批图展示成功主链，提交后的审计由图 38 单独展开。跨请求图省略 Controller、重复行锁及 OO 授权适配跳转，沿用审批详图；仍有有效来源时继续允许的分支见图 42。CRUD 权限拒绝和状态冲突由活动图、状态图与时序注记共同说明；并发见图 41。包图省略 DTO/VO、安全配置和外部框架，不当作部署图使用。

## 命令行生成和导出

在 C2 目录执行：

```powershell
./docs/design/export-staruml.ps1
```

需要自定义安装路径时：

```powershell
./docs/design/export-staruml.ps1 -StarUml 'C:\Program Files\StarUML\StarUML.exe'
```

生成器使用 Python 3 标准库，不额外安装绘图库。导出脚本调用 StarUML 官方 `image` 命令，而非第三方渲染器：

```powershell
& 'C:\Program Files\StarUML\StarUML.exe' image './docs/design/C2-RBAC1.mdj' -f svg -o './docs/design/exports/<%=filenamify(element.name)%>.svg'
& 'C:\Program Files\StarUML\StarUML.exe' image './docs/design/C2-RBAC1.mdj' -f png -o './docs/design/exports/<%=filenamify(element.name)%>.png'
```

PNG 原生导出为透明背景，脚本仅合成白底以适配图片预览，保留所有内容及试用水印。当前机器 StarUML 处于试用状态，因此导出预览有 `UNREGISTERED` 水印；正式课程材料可由具备许可的 StarUML 用同一模型重新导出。日志位于 `%TEMP%/c2-staruml-svg.log`、`%TEMP%/c2-staruml-png.log`，对应 `.err` 文件保留 stderr。参考 [StarUML CLI 官方文档](https://docs.staruml.io/user-guide/cli-command-line-interface/)。

只重新导出已在 StarUML 手工调整的图源时使用 `-ExportOnly`；不加该参数会按脚本重建 `.mdj`，覆盖手工排版。需要长期维护的调整应同步到 `build_staruml.py` 或 `build_supplementary.py`，让模型、索引和导出保持一致。

## Service 与 GRASP 的代码证据

完整专项说明见 [grasp与项目.md](../../grasp与项目.md)，含九项原则、API 调用主线、源码/图件/测试索引、取舍和答辩追问。下表保留本目录的简要职责对照。

| 职责或原则 | func 实际代码 | oo 实际代码与解释 |
| --- | --- | --- |
| 用例控制 | `RoleInheritanceService.change` 在事务内组织锁、查询、校验和写入；`BusinessRequestService.change/applyChange` 处理申请流程及状态 | `RoleInheritanceService.change` 同样负责事务和管理边界；`BusinessRequestService` 通过三个窄端口协调申请用例。Controller 负责 HTTP 适配，不包含领域状态转换 |
| Information Expert | `RoleInheritanceRules` 接收显式邻接表完成图计算；申请状态判断集中在 Service 的静态过程 | `RoleHierarchy` 持有不可变的关系快照并计算可达性与环路；`BusinessRequest` 持有状态并在 `approve/rename/withdraw/reject/delete` 中维护不变量 |
| 高内聚 | func 将相关业务步骤集中维护，便于顺序跟读；Graph record 委托静态规则 | OO 将申请状态规则放入纯 Java 领域对象，Spring/JPA 的装载、行锁和保存放入基础设施；职责边界可由 `BusinessRequestTest` 和 `RequestArchitectureTest` 检查 |
| 低耦合与 Indirection | func Service 直接依赖 Spring Data Repository 与统一授权 Service | OO 申请 Service 依赖 `RequestRepository/RequestSystems/RequestAccess`；`JpaRequestRepository` 和 `RequestContextAdapter` 负责适配，角色继承也通过 `RoleInheritanceStore` 端口读取和写入 |
| Polymorphism | 框架仓储存在接口机制，但不因此把整版视为面向对象领域设计 | 申请用例面向端口调用；实际运行注入 JPA 和授权适配器。接口实现的方向由类图空心三角标明，不凭类名或包名判断设计差异 |
| Protected Variations | 两版共用 OpenAPI、数据和验收输入以固定外部行为 | 独立领域模型使申请规则测试不必启动 JPA；基础设施实现负责与稳定 C1 授权入口衔接，领域模型不直接访问 Redis/MySQL |

两版的有效权限计算仍复用 C1 `AuthorizationService` 的 Service/JPA 组织策略实现。OO 的明确变化在角色图封装和申请领域及端口，并未把所有 C1 模块改成领域 Service。两版在相同输入下应返回相同 HTTP 状态、权限和业务结果，差异体现在职责、依赖、状态封装及测试边界。

继承方向为 `senior → junior`：高级角色取得低级角色的权限。新增边须同归属，两个共享角色由全局管理员维护；组织停用优先于角色继承及全局身份。`REOPENED` 需要同一直接权限来源满足所有适用重开组织，不能拼接不同来源。Redis 仅负责身份会话，实时权限从当前版 MySQL 读取；跨请求图明确要求管理员删边事务已提交后再发起员工下一次请求。

## 本轮验证与维护范围

执行 `python ./docs/design/build_staruml.py --check` 校验模型引用、导出文件、StarUML 自动扩框后的类框重叠和实际关系线穿框。生成阶段还校验 Java 类/方法/关联字段、包 import、OpenAPI operationId、测试方法、同步调用者与接收者的执行条范围、控制片段底边，以及补充图的输入节点与连线路径。方法参数、返回值、可见性和 static 标志从源码声明读取；部分长参数在图上折叠为方法名，完整签名仍在模型中。

绘图工具的防错回归：`python ./docs/design/test_diagram_checks.py`，8 项覆盖泛型、重复 ID、悬空引用、绕障路径、片段与调用者执行条边界、真实静态签名和字段关联。源代码及排版复核结果见 [图件复核记录](./图件复核记录.md) 和 [绘图 skill 核对记录](./绘图skill核对记录.md)。自动检查不能替代 PNG 目视复核或业务回归。

本轮仅新增图源、生成与校验工具、索引及职责说明，没有修改后端业务代码、数据库或前端。既有后端和浏览器测试只作为追踪证据，本轮不重复宣称重新执行了业务回归。接口与类职责变更时，应更新生成数据、重新导出并复查相关测试。

本轮图件覆盖开发方案中已实现的主题，并非为全部 46 个 API 逐个画独立图。前端路由图仅反映当前 Vite 开发和预览代理；课程 Swarm 拓扑待队友实际集群参数明确后另画。后续顺序以 [C2 收尾清单](../../收尾清单.md) 为准：后端代码和模块说明已整理完成，记录见 [后端整理说明](../后端整理说明.md)；测试重组与本机回归也已完成，三模型比较与 GRASP 专项现均已完成，[总结](../../项目总结.md)、`答辩文档.md`（本地待完善，暂不上传） 和 [交接说明](../../部署交接文档.md) 现已交付，部署配置已交付，接着推进实际集群验收；本目录源码与测试索引已同步到测试目录。JaCoCo 结果图、命令和输出 TXT 随实际执行结果归档到 C2 的 `测试/`。
