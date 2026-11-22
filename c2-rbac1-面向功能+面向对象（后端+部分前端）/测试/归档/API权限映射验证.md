> 历史归档：保留整理前的阶段记录；其中“当前”“待完成”和测试数量均属于原批次。现行操作及状态以[统一说明](../README.md)为准。

# 第五步：API权限映射及预查询验证

日期：2026-11-21（Asia/Shanghai）。本阶段完成两版后端API映射与预查询；前端在第6步接入，整轮验收及Apifox同步在第7步进行。

## 实现与行为

`GET /api/auth/me/api-check?systemCode=demo-engineering-app&operationId=approveRequest`

成功响应：

```json
{"systemCode":"demo-engineering-app","operationId":"approveRequest","allowed":true}
```

- 当前员工只来自可信Session身份；不能传入他人的employeeId或username切换查询对象。
- systemCode必填、非空白且最多64字符；operationId必填、非空白且最多128字符。成功和错误均不缓存。
- 先验证系统存在与准入，再检查API映射。未知系统404 SYSTEM_NOT_FOUND，系统越界403 SYSTEM_ACCESS_DENIED，未登记API404 API_NOT_FOUND；已登记且没有全部权限为200 allowed=false。
- 固定ALL。映射空集合、引用丢失、跨系统或与规范定义不一致返回503 AUTHORIZATION_UNAVAILABLE，不将空集合当作放行。
- 复用有效权限计算，包含RBAC1继承、实时撤权、组织DISABLED优先及REOPENED同源重授；全局管理员不能绕过组织停用。
- 预查询不检查具体申请存在性、申请人和状态。实际操作重新授权并执行对象规则，预查询结果不是操作凭证。

## 结构与迁移

两版新增三份生产Java文件：

| 文件 | 作用 |
| --- | --- |
| authentication/vo/CurrentApiCheckVO.java | 仅公开系统、操作和判断结果 |
| authorization/ApiOperationRegistry.java | JdbcTemplate读取两表、校验映射、幂等初始化及删除引用保护 |
| simulation/RequestOperation.java | 实际申请入口与映射共用的八个操作定义及有序ALL权限集合 |

AuthController处理输入输出；CurrentAuthorizationService组织查询；AuthorizationService复用可信身份、系统准入和原有isAllowed计算。原角色图实现、OO三个申请端口和领域对象、func过程式状态变更、CloneFactory映射均保留。没有新增映射管理界面。

两版新增且内容相同的V9__api_operation_permissions.sql：

| 表 | 键及约束 |
| --- | --- |
| api_operation | 主键(system_id,operation_id)，系统外键；operationId区分大小写 |
| api_operation_permission | 主键(system_id,operation_id,permission_id)，操作和权限外键 |

V9只为已有的四个演示申请系统补映射，共32个操作、52条权限要求；不授角色、不修改继承和组织策略。空库先迁移，再由原有initializeDemo在权限准备完成后补映射。重复初始化只补不存在的操作；已有损坏配置会失败回滚，不悄悄覆盖。权限维护在删除被映射引用的权限时返回409 RESOURCE_CONFLICT。

对应八个申请operationId及权限集合见[契约第7节](../../api/统一身份鉴权接口契约.md)。这八个是API预查询的业务目标，不是参考文档的八个身份接口。

## 实际执行结果

所有源码和配置在C2测试目录；通过真实MySQL8和Redis验证，两版分别使用专用测试库。没有停止或重启18181/18182旧开发服务。

| 批次 | func | oo | 说明 |
| --- | --- | --- | --- |
| RED | 3项，3失败 | 3项，3失败 | 无错误/跳过；新增接口被旧安全规则拒绝，状态或业务错误码不符合契约 |
| 定向 | 3项通过 | 3项通过 | 最初三个边界用例通过，不代表完整验收 |
| GREEN（首次构建，失败） | 0项，编译失败 | 0项，编译失败 | 夹具误用实体没有的setter，已改为现有构造器 |
| GREEN-final（夹具修正前全量，失败） | 87项，2错误 | 91项，2错误 | 同组织第二系统误设默认项，违反唯一约束；已设为非默认系统 |
| GREEN-verified（最终完整） | 88项通过 | 92项通过 | 0失败、0错误、0跳过，verify及打包成功 |

最终两版各执行65项共同HTTP测试，其中本阶段新增11项API测试；另新增2项共同迁移测试。原可信员工8项及既有角色图、初始化、登录、工作区、申请生命周期等均参加完整回归。

| 最终覆盖率 | func | oo |
| --- | --- | --- |
| 行 | 95.04%（1456/1532） | 95.19%（1483/1558） |
| 分支 | 84.35%（485/575） | 84.28%（488/579） |

最终证据在[GREEN-verified](../结果/API映射-20261121/GREEN-verified)：各版结果JSON/TXT、命令、命令行输出、Surefire XML、JaCoCo HTML/XML及真实报告截图。保留失败尝试，不把失败批次或旧报告当作通过。

- [func结果](../结果/API映射-20261121/GREEN-verified/func/结果.json)、[JaCoCo](../结果/API映射-20261121/GREEN-verified/func/jacoco/index.html)、[结果图](../结果/API映射-20261121/GREEN-verified/func/JaCoCo结果.png)
- [oo结果](../结果/API映射-20261121/GREEN-verified/oo/结果.json)、[JaCoCo](../结果/API映射-20261121/GREEN-verified/oo/jacoco/index.html)、[结果图](../结果/API映射-20261121/GREEN-verified/oo/JaCoCo结果.png)

## 重点场景

| 场景 | 测试 |
| --- | --- |
| 匿名、参数边界、未知系统/API、先检查系统范围 | AuthSessionTest.apiInputBoundary / apiUnknownTargets / apiSystemIsolation |
| ALL、create不依赖read、直接撤权与实际接口拒绝 | apiAllAndLiveRevocation |
| 八个操作与同码跨系统隔离、大小写和未映射登录 | apiEightOperationsAndSystemSeparation |
| 继承路径撤销即时生效 | apiInheritedAuthorization |
| 组织停用、重开及重授撤销 | apiOrganizationPolicies |
| 空映射、降低权限要求、跨系统引用、未知定义 | apiCorruptMappingsFailClosed |
| 查询成功但对象越权/状态冲突；映射不替代实际授权 | apiActualObjectAndStateAuthorization |
| 客户端不能切换身份、员工停用使旧会话失效 | apiTrustedActorOnly |
| 映射引用删除保护、全局管理员仍受停用约束 | apiReferencedPermissionCannotBeDeleted |
| 空库V1～V9、已有V8数据升级、32/52映射、保留撤权/停用、重复迁移 | ApiMigrationTest两项 |
| 重复初始化保留继承撤销，映射不重复；损坏映射不自动修复 | 两版InitializationTest扩展 |

迁移测试创建单次UUID命名的独立临时测试库，只清理本用例创建的单个库；不清空或重建应用库。映射权限外键阻止正常路径产生悬空引用，读取时仍采用LEFT JOIN并检查引用完整性。

## 运行与后续

从C2根目录运行：

```powershell
python 测试/脚本/run_backend.py func --output-dir 测试/结果/API映射-20261121/GREEN-verified/func --build-dir target/identity-step5
python 测试/脚本/run_backend.py oo --output-dir 测试/结果/API映射-20261121/GREEN-verified/oo --build-dir target/identity-step5
node 测试/前端/report-images.mjs 测试/结果/API映射-20261121/GREEN-verified
```

重复验收请换新的output-dir批次，保留本次证据。运行器从本机Docker读取依赖凭据，仅传入子进程环境，不写入命令记录。迁移专项需要测试MySQL账号可创建/清理限定临时测试库。可运行JAR位于两版target/identity-step5；正式启动时按原部署配置连接各自数据库，Flyway自动执行V9；需要演示数据时另行执行原有--init-demo。

主OpenAPI2.4.0描述已验证行为；原目标文件保留八项后端验收快照，不作为第二份正式接口全集。[静态契约结果](../结果/API映射-20261121/静态契约.txt)、[源码/证据核对结果](../结果/API映射-20261121/复核结果.json)与[复核命令](../结果/API映射-20261121/复核命令.txt)归档在本批次根目录；可通过测试/脚本/check_api_evidence.py重做第五步证据核对。

未执行：新增前端页面/按钮接入、浏览器回归、真实原表全量重验、课程Swarm、跨项目联调和JWT。23项浏览器结果仍是11月19日历史批次。后续先完成第6步现有页面接入，再做第7步整轮验收与Apifox同步。
