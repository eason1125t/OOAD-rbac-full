# 教师绘图 skill 核对记录

适用基线：2026-11-19，本目录 `C2-RBAC1.mdj` 中的 9 张类图与 16 张时序图。全部 42 张中，其余为 2 张包图、3 张 ER 图、3 张组件结构图、8 张活动图和 1 张状态图，按各自图法检查。

本轮已读取并使用老师提供的 [SKILL.md](../../../项目整体方向规划和技术细节/老师提供的skill/uml-class-sequence-diagrams/uml-class-sequence-diagrams/SKILL.md) 及 [references/checklist.md](../../../项目整体方向规划和技术细节/老师提供的skill/uml-class-sequence-diagrams/uml-class-sequence-diagrams/references/checklist.md)。下面区分规范检查与图的取舍，便于复查。

| 检查项 | 实施与核查方式 |
| --- | --- |
| 以现有代码为依据 | 逐个读取两版 Service、Graph/RoleHierarchy、申请领域对象、仓储和适配器。生成器检查对应 Java 文件、字段、方法声明和访问级别，不把规划中的满血小样画为已实现 |
| Service 居中 | 九张类图采用中心 Service、周围协作类布局，中心淡蓝色；有效权限选 AuthorizationService，组织策略选 OrganizationPermissionService，登录工作区选 WorkspaceService |
| 类的三个区 | 原生 Class/Interface 的名称、属性、操作分区；接口显示 `«interface»`；未展示无关成员；静态方法下划线由 StarUML 绘制 |
| 字段关联 | 原生 Association，实线、可导航空心箭头，role name 标注关联字段；标签位于箭头上方或左侧 |
| 方法局部与静态依赖 | 原生 Dependency，虚线空心箭头，不添加线中文字；Repository 的实体类型由方法签名表达 |
| 接口实现 | 原生 InterfaceRealization，从 JpaRoleInheritanceStore/JpaRequestRepository/RequestContextAdapter 指向各接口，虚线空心三角落在接口侧 |
| 无重叠、无穿框 | 生成前检查布局；导出后解析实际 SVG 再查 73 个类框及关系线路。修正了 StarUML 自动拉直绕行线的问题，必要的避让线保留正交折点。其他图的输入节点与非端点穿框由补图脚本检查 |
| 生命线格式 | 最左 `obj: 调用者`；普通对象一行 `objectName: ClassName`；静态规则、审计和 CloneFactory 使用 `«MetaClass»` 与 `: ClassName` |
| 消息与编号 | 原生同步消息实线实心箭头，自定义连续编号；回复/异常为 reply，虚线空心箭头、无编号。赋值型消息不再重复画普通返回 |
| 独立执行条 | 每条同步消息使用自己的原生 Activation；检查发送者正在激活，并检查接收者完整执行区间被调用者覆盖；连续仓储查询和权限检查不合并执行条。事务提交及回调的执行条已按此修正 |
| 消息落点 | 交给 StarUML 的 LinePart/Message/Activation 排版计算接收执行条顶边端点；生成器检查发送时仍处于激活区间，包括异常消息 |
| 异常路径 | 继承错误由 Service 直接到调用者；撤权拒绝由实际 AuthorizationService 到捕获者 BusinessRequestAudit，再由捕获者重新抛出。不添加未捕获异常的 Service 逐层传递 |
| 控制片段 | 条件用 opt、候选遍历用 loop，独立请求顺次发生用 seq；保留条件文字与首条消息间距，片段置底；接收执行条不得越出片段底边 |
| 静态调用 | 功能版 applyChange 改为独立 «MetaClass» BusinessRequestService；RoleInheritanceRules、BusinessRequestAudit、CloneFactory 及静态事务同步管理器采用同样符号 |
| SVG/PNG 与目视检查 | 由 StarUML 官方 CLI 导出 42 张 SVG 和 42 张 PNG，通过 PNG 总览逐张检查，并放大复核关键分支、静态调用与提交回调；PNG 只合成白底，试用水印完整保留 |
| 可维护与可追踪 | `build_staruml.py` 与 `build_supplementary.py` 共用模型工厂及 ID；`export-staruml.ps1` 按索引数量导出；逐图索引关联需求、operationId、源码、测试与前端 |

图中有意省略的部分：单个 CRUD 时序省略重复 Controller 及审计包装跳转，提交审计在图 38 单独展开；跨请求图以响应标记已提交，省略重复行锁及 OO 适配跳转。替代有效来源用图 42 展开，并发状态竞争用图 41 表现两种可能胜者。图 34 是已实现开发代理的协议级协作，框架写 Redis 箭头不表示控制器直接调用 Redis。完整异常集合以 OpenAPI 和测试为准，不把这些图当作所有 46 个 API 或整个系统依赖的穷举。模型比较本轮不加入。

本轮命令行复核结果：生成器校验通过；StarUML CLI 分别报告 `Total 42 diagrams were exported`（SVG、PNG）；导出检查通过 2,978 个唯一图元、6,647 个有效引用及 73 个实际渲染类框。绘图工具 8 项防错回归通过，PNG 已逐张通过总览并对关键图放大复核。详见 [图件复核记录](图件复核记录.md)。本轮未重新执行业务测试，也未提交或推送 Git。

## 2026-11-21 统一身份扩展复核

本节新增当前47图基线，前文42图数字保留为11月19日历史记录。本轮更新已移除的WorkspaceService.findActiveEmployee引用和仓储关联，改为CurrentEmployeeService；更新前端CurrentAccess/DepartmentEmployees及申请按钮权限流程，新增43～47身份/目录类图、API复合外键ER和两张活动图。

签名解析修复带括号正则的Pattern注解，不把注解残留画成类型；HTTP长签名按名称折叠，完整声明保留。9项绘图工具测试通过，原生CLI导出47组SVG/PNG；3339图元、7338引用、86个渲染类框检查通过。实际查看新增与受影响图；保留试用水印、原编号及功能版/OO差异。最终业务回归88/92、浏览器35另行实测，[完整证据](../../测试/README.md)。

## 2026-11-22 JWT迁移交付复核

按现有认证源码和老师UML skill更新原图并新增48/49，原生模型/索引/SVG/PNG共49组；3534图元、7683引用及96实际类框通过，绘图工具9项通过。类图Service居中、字段关联及静态依赖按源码检查；时序图逐次激活、未捕获异常直接回调用者、独立请求seq片段保留。源代码与业务逻辑未因画图变更。

目视打开新增生命周期/轮换图以及受影响登录、工作区、前端图；保留水印，不把本机图件核对当作Swarm或重新执行业务测试。实际证据见[本轮目录](../../测试/结果/JWT交付同步-20261122)，业务194/198及浏览器51等证据见[JWT整体验收](../../测试/README.md)。
