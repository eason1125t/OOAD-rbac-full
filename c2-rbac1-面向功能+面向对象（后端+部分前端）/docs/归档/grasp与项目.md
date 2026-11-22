> 历史归档：保留整理前的阶段记录；其中“当前”“待完成”和测试数量均属于原批次。现行操作及状态以[统一说明](../项目总结.md)为准。

# GRASP 与 C2 项目

更新日期：2026-11-19。对应 C2 收尾第 4 项，以当前两套后端源码、已有 UML 和测试为依据。本文用于解释“一个 API 的职责为什么交给这些对象”，也可作为最终答辩文档的设计部分输入。

## 1. 阅读口径

C2 的功能要求是 RBAC1：角色继承、有效权限、组织策略与业务申请。GRASP 讨论的是完成这些要求时如何分配对象职责，不增加 RBAC 的权限能力。

本文以对象版为主要实例，以功能版作对照。对象版的明确改造在 `RoleHierarchy` 和申请模块的 `domain/port/infrastructure`；认证、组织策略和部分授权仍沿用 C1 的 Service/JPA 组织方式。因此不能将整个对象版称为已经完全实现 DDD。

九项原则的简要含义参考 Nantes Université 的 [GRASP 课程讲义](https://gl.univ-nantes.io/design/slides/grasp.html)，著作背景见 Craig Larman 的 [Applying UML and Patterns](https://www.craiglarman.com/wiki/index.php?title=Book_Applying_UML_and_Patterns)。下文对项目的对应关系是基于源码的设计分析，不是外部资料对本项目的评价。

| 原则 | 在本项目中主要回答的问题 | 主要实例 |
| --- | --- | --- |
| Information Expert，信息专家 | 谁掌握完成判断所需的信息？ | RoleHierarchy 的关系计算；BusinessRequest 的状态与申请人规则 |
| Creator，创建者 | 谁掌握初始化资料或负责记录对象？ | JpaRoleInheritanceStore 装载关系快照；申请 Service 发起创建并委托工厂 |
| Controller，控制器 | 谁接收并协调一个系统用例？ | 两类应用 Service；HTTP Controller 负责协议适配 |
| Low Coupling，低耦合 | 如何减少变化的传播？ | 申请 Service 依赖三个业务端口，领域对象不依赖 JPA |
| High Cohesion，高内聚 | 同一对象的职责是否集中？ | 申请状态规则、持久化、HTTP 映射和审计分别集中维护 |
| Polymorphism，多态 | 调用方能否通过稳定类型使用具体实现？ | RequestRepository → JpaRequestRepository 等接口实现关系 |
| Pure Fabrication，纯虚构 | 哪些技术协作值得由专门对象承担？ | 存储适配器与 BusinessRequestAudit |
| Indirection，间接性 | 谁隔开原本会直接耦合的双方？ | RequestContextAdapter 隔开申请用例与现有授权/JPA 系统模型 |
| Protected Variations，受保护变化 | 哪种变化应被稳定边界吸收？ | 持久化细节由端口/适配器吸收，公开行为由共同契约和回归约束 |

这些原则可以共同解释一项设计，没有“一段代码只能属于一个原则”的限制。是否合理仍需看实际收益与额外复杂度。

## 2. 主线一：新增角色继承

接口：`PUT /api/roles/{roleId}/parents/{parentRoleId}`，OpenAPI `operationId=addRoleParent`。需求关联 C2-01、C2-03、C2-05、C2-07。

继承方向为 `senior → junior`，高级角色取得低级角色的权限。“主管继承审批员”不是 Java 类之间的 `extends`。

### 2.1 实际调用与职责

| 顺序 | 类与方法 | 分配给它的职责及原因 |
| --- | --- | --- |
| 1 | [RoleInheritanceController.add](../../backend-oo/src/main/java/rolepermission/RoleInheritanceController.java) | 从可信认证上下文取得 actor，校验路径参数，调用用例并映射 HTTP 响应 |
| 2 | [RoleInheritanceService.change](../../backend-oo/src/main/java/rolepermission/RoleInheritanceService.java) | 协调管理员资格、锁、端点读取、范围、幂等、查环、增量授予与保存，持有事务边界 |
| 3 | [RoleInheritanceStore](../../backend-oo/src/main/java/rolepermission/RoleInheritanceStore.java) / [JpaRoleInheritanceStore](../../backend-oo/src/main/java/rolepermission/JpaRoleInheritanceStore.java) | 提供锁、直接边判断、快照和写入；将 JPA 细节隔离在实现中 |
| 4 | [RoleHierarchy.wouldFormCycle](../../backend-oo/src/main/java/rolepermission/domain/RoleHierarchy.java) | 利用自身关系快照判断 junior 是否已经能到达 senior |
| 5 | [AssignmentService.requireInheritanceGrant](../../backend-oo/src/main/java/assignment/AssignmentService.java) | 结合上游依赖方、实际分配组织和新增权限来源，判断新增继承是否越权恢复或扩大授权 |
| 6 | Store.add，随后事务提交 | 校验通过才保存直接边；事务提交后后续请求才能按新关系授权 |

`change` 的新增分支依次执行管理资格 → 全局图写锁 → 双端角色与管理范围 → 同归属 → 自继承 → 直接边幂等 → 环路 → 增量授予检查 → 写入。删除分支在范围检查后移除并返回，不能照搬新增分支的顺序描述。

### 2.2 为什么这样分配

**信息专家：** RoleHierarchy 拥有不可变邻接表，适合维护正向闭包、反向依赖和查环。`wouldFormCycle` 从 junior 出发展开，若到达 senior，新增 senior→junior 就会闭环。队列与已访问集合保证多路径去重；构造器复制 Map 和内部 Set，调用方不能修改已装载快照。

但图对象没有操作者管理范围、员工分配与组织重授数据，不能仅凭“信息专家”把全部授权规则也塞进去。AssignmentService 掌握并组合这些信息，RoleInheritanceService 负责协调它们。这体现职责应按所需信息协作分配，而非集中成一个全知对象。

**控制器与高内聚：** RoleInheritanceService 是该用例的协调者，RoleHierarchy 是计算对象。当前图对象不增加边、不保存，也不自行启动事务。Service 保留流程与跨对象一致性，领域对象集中维护图规则。

**创建者：** JpaRoleInheritanceStore.snapshot 读取边并组织邻接表，掌握快照初始化资料，因此由它 `new RoleHierarchy(parents)`。不是由 HTTP Controller 拼图，也不是通过 Spring 单例共享一份会变旧的关系快照。

**低耦合与间接性：** Service 经 RoleInheritanceStore 使用存储能力，图规则可脱离数据库验证。不过该 Service 仍直接使用 RbacRoleRepository、AuthorizationService 和 AssignmentService，边界是局部改善，并非彻底隔离全部基础设施。

### 2.3 一致性不由原则名称自动保证

两次写入可能各自看起来合法、合并后却成环。真正的保护来自 [RoleInheritanceRepository.lockGraph](../../backend-oo/src/main/java/rolepermission/RoleInheritanceRepository.java) 对同一锁记录执行 `FOR UPDATE`，以及 Service 的 `READ_COMMITTED` 事务、先锁后读和相关写入口的协作约定。Java 的不可变快照不能替代数据库并发控制。

图件见 [新增继承类图](../design/exports/10-oo-新增角色继承-类图.png) 和 [时序图](../design/exports/11-oo-新增角色继承-时序图.png)。规则证据见 [RoleHierarchyTest](../../测试/后端/oo/java/rolepermission/domain/RoleHierarchyTest.java) 中的 `rejectsSelfInheritance`、`rejectsClosingAnExistingPath`、`acceptsAnAcyclicEdge`、`expandsDiamondWithoutDuplicates`、`findsAllDependingRoles`；真实并发证据关联共同 HTTP 测试的 `serializesInheritanceWritesWithDisjointEndpoints`。

## 3. 主线二：审批与申请生命周期

接口：`POST /api/requests/{requestId}/approve`，`operationId=approveRequest`。修改、撤回、驳回、删除复用相近协调流程，需求关联 C2-11 至 C2-16。

### 3.1 从 HTTP 到领域对象再到数据库

1. [BusinessRequestController.approve](../../backend-oo/src/main/java/simulation/BusinessRequestController.java) 接收可信 employeeId 和路径参数，调用 Service；用例成功提交后才返回成功响应。
2. [BusinessRequestService.approve/change](../../backend-oo/src/main/java/simulation/BusinessRequestService.java) 在事务中锁申请，读取真实系统归属，先校验 `request:read`，再校验 `request:approve`。
3. [BusinessRequest.approve](../../backend-oo/src/main/java/simulation/domain/BusinessRequest.java) 根据自身状态执行转换；已审批抛兼容错误，其他非法状态由 `requirePending` 拒绝。
4. [JpaRequestRepository.save](../../backend-oo/src/main/java/simulation/infrastructure/JpaRequestRepository.java) 将领域变更写回持久化模型，数据库事务决定最终提交或回滚。
5. [BusinessRequestAudit.record](../../backend-oo/src/main/java/simulation/BusinessRequestAudit.java) 在事务完成回调中确认结果；写入只有实际提交才记录 `COMMITTED`。

Service 不从客户端接受申请的 systemId 或 applicantId 来覆盖记录。领域对象也不接收一个客户端声称“已经有权限”的布尔值。

### 3.2 信息专家与高内聚：把状态规则放在申请上

BusinessRequest 已有申请人、状态和删除标记，因此由它维护：

| 领域方法 | 本对象负责的不变量 | Service 仍负责的外部条件 |
| --- | --- | --- |
| rename / withdraw | 本人、待审批、未删除 | 查看权与对应动作权、锁及事务 |
| approve / reject | 待审批、未删除；重复审批保留既有错误码 | 当前系统的查看权与审批/驳回权 |
| delete | 本人、已驳回、未删除 | 查看权与删除权、当前时间和持久化 |
| create | 初始化待审批状态、规范化标题 | 可信身份、解析系统、创建权及输入格式合法性 |

审批和驳回没有“禁止审批本人申请”的规则，不能为了展示状态封装自行添加职责分离限制。输入非空、长度等格式校验仍在 DTO/Controller；领域方法不是替代接口校验的万能入口。

领域对象没有 JPA/Spring 注解和外部仓储字段；状态测试可直接构造对象运行。其公共全参构造器也用于持久化还原，所以当前实现并没有用类型系统杜绝所有非法初始组合：这是一项真实边界，不能宣传成绝对封闭的不变量模型。

### 3.3 三个端口的低耦合、间接性与多态

| 应用服务字段类型 | 当前运行实现 | 隔开的变化 |
| --- | --- | --- |
| [RequestRepository](../../backend-oo/src/main/java/simulation/port/RequestRepository.java) | JpaRequestRepository | 分页、JPA 实体转换、申请行锁与保存 |
| [RequestSystems](../../backend-oo/src/main/java/simulation/port/RequestSystems.java) | RequestContextAdapter | 系统代码/ID 到业务系统的解析 |
| [RequestAccess](../../backend-oo/src/main/java/simulation/port/RequestAccess.java) | [RequestContextAdapter](../../backend-oo/src/main/java/simulation/infrastructure/RequestContextAdapter.java) | 调用现有统一授权及当前可用动作计算 |

Service 通过接口接收具体对象，实际调用由实现响应，这是多态机制。当前每个端口只有一个运行实现，并没有按业务类型切换多套算法的完整实例；不能称为已经落地的多实现策略体系，也不需要为凑原则添加空实现。

RequestContextAdapter 同时实现两个紧邻的上下文端口，避免新增两个只转发一行的类。它把申请用例需要的 RequestSystem 转成现有授权服务所需的 BusinessSystem，申请 Service 无需导入这些 JPA 实体。增加实现仍必须遵守原有锁、过滤和授权语义，不能只满足方法签名。适配器的 `allowedActions` 还引用 BusinessRequestService.REQUEST_ACTIONS 动作清单，因此这不是全部依赖都严格单向的架构；若今后多个用例共享动作定义，再考虑提取稳定的业务类型，当前不为此增加新层。

[RequestArchitectureTest.dependsOnPorts](../../测试/后端/oo/java/simulation/RequestArchitectureTest.java) 以反射检查 Service 的非静态字段类型属于业务端口，并检查方法参数不出现 Spring Data 类型或 JPA 实体。它是边界回归，检查范围不包含所有方法体、返回类型和传递依赖，不能作为“全项目架构完美”的证明。

### 3.4 纯虚构：专门承担技术协作

JpaRequestRepository 不是现实业务中的“申请”本身，而是专门承担存储适配的对象；BusinessRequestAudit 集中承担结构化审计与事务结果确认。把这些责任从申请对象移开，能让状态规则保持集中，也避免每个写入口重复处理提交日志。

BusinessRequestAudit 是静态工具容器，说明纯虚构不要求一定声明接口。日志依赖 Spring 的事务同步，领域对象不承担日志注册；审计也不是能保证投递的持久化消息或事务外盒，进程崩溃后的日志完整性不能由此保证。

CloneFactory 用于服务结果和 DTO/VO 的同名字段映射，减少重复赋值；它不是 GRASP Creator 的同义词，也不负责角色授权、状态转换或事务。本项目保留这一映射工具，但不为展示工具而制造多余层次。

相关图件：[审批类图](../design/exports/14-oo-审批申请-类图.png)、[审批时序图](../design/exports/15-oo-审批申请-时序图.png)、[申请状态图](../design/exports/25-shared-申请生命周期-状态图.png)、[事务与审计](../design/exports/38-shared-事务提交与审计-时序图.png)。

## 4. 创建者与受保护变化的具体边界

申请 Service 在授权后掌握 systemId、可信申请人和标题，负责发起 `BusinessRequest.create`；工厂集中新申请的初始状态。JpaRequestRepository 掌握数据库字段，负责还原已有对象，并在新建保存时创建 SimulatedRequest。这是“谁拥有初始化资料，谁承担相应创建工作”的项目实例，静态工厂只是实现方式。

“受保护变化”在本项目有两层：申请的存储与授权接入细节由端口/适配器边界吸收；func/oo 的内部职责差异由共同 [OpenAPI](../../api/openapi.yaml) 和相同 HTTP 验收约束其公开行为。共同契约是边界约束，测试是变化检测手段，不是运行时替代实现。

更换数据库不等于只改适配器即可完成。申请行锁、事务隔离、分页语义、错误码和迁移数据都要重新验证；组织授权业务规则若变化，也可能同时影响 Service、授权模块与契约。这些不属于接口可以自动屏蔽的技术细节。

## 5. 主线三：原会话实时撤权

场景：员工审批成功 → 管理员删除唯一继承路径并提交 → 员工使用原会话再次审批被拒绝；若仍有合法替代路径则继续允许。需求关联 C2-04、C2-06、C2-15。

[AuthorizationService.requireBusinessPermission/effectivePermissions](../../backend-oo/src/main/java/authorization/AuthorizationService.java) 统一组合系统准入、组织策略、直接角色和当前继承闭包。RoleHierarchy 只计算关系，AuthorizationService 决定这些来源在当前组织策略下是否有效。Redis 会话保存身份，不以登录时权限快照代替本次 MySQL 判权。

这是职责协作和规则复用的实例：审批、查看与前端动作展示使用现有统一授权入口；客户端显示动作只帮助交互，写入口仍独立检查。组织停用优先于全局身份；重开组织所需的重授必须由同一权限来源满足，不能拼接不同来源。

该模块仍依赖多个 JPA 仓储并承担复杂策略，是后续维护关注点。当前没有额外抽出一个纯领域策略对象，也不将它虚写成已实现。后续若规则明显增长，可先增加纯规则边界测试，再判断是否值得拆分。

图件见 [同会话实时撤权](../design/exports/16-oo-同会话实时撤权-时序图.png)、[多路径权限与删边](../design/exports/42-shared-多路径权限与删边-活动图.png)；测试关联 `inheritanceRevocationAppliesToExistingSession`、`requiresReadPermissionForRequests`、`matchesReopenedScopesToOneInheritedPermissionSource`。

## 6. func、oo 与满血小样如何比较 GRASP

| 关注点 | 功能版 | 对象版 | 满血内存小样 |
| --- | --- | --- | --- |
| 图计算 | Graph 保存数据，静态 Rules 接收显式关系 | RoleHierarchy 保存快照并执行规则 | FullRoleHierarchy 执行规则并协调端口 |
| 申请状态 | BusinessRequestService.applyChange 中检查并赋值 | BusinessRequest 的方法维护状态 | 小样不包含申请模块 |
| 用例协调 | Service 依次执行步骤 | Service 协调事务、端口与领域对象 | 领域对象协调选定用例的装载和保存 |
| 适合讲解的差异 | 流程直观、纯计算可复用 | 信息与行为结合，申请边界较清楚 | 领域协作增多，依赖和测试准备也增多 |

对照源码：[func 的申请流程](../../backend-func/src/main/java/simulation/BusinessRequestService.java)、[func 的静态图规则](../../backend-func/src/main/java/rolepermission/RoleInheritanceRules.java)。具体三模型定义、图件和小样证据统一见 [三模型比较](../../三模型比较/README.md)。

功能版同样可以有高内聚、较低耦合和合理的用例控制；使用 Service、静态函数并不自动违反 GRASP。对象版的改进要由职责和依赖事实解释，而非只说“有接口所以更好”。满血小样将协作下沉后，不能自动得到更高内聚或更低耦合，其内存验证也不能替代正式数据库并发与 HTTP 授权证据。

## 7. API—图件—测试索引

所有共同 HTTP 测试入口均为 [AuthHttpTest](../../测试/后端/common/java/contract/AuthHttpTest.java)，分别由 func/oo Maven 配置执行；完整追踪见 [测试需求追踪](../../测试/归档/需求追踪.md)。

| 入口/需求 | 优先讲解的原则 | 证据入口 |
| --- | --- | --- |
| addRoleParent；C2-01/03/05/07 | 信息专家、创建者、控制器、低耦合 | 本文第 2 节的类图/时序；RoleHierarchyTest；`serializesInheritanceWritesWithDisjointEndpoints`、`rejectsAssignmentsThatWouldRestoreDisabledInheritedPermission` |
| createRequest；C2-11 | 创建者、控制器 | [发起修改时序](../design/exports/32-oo-申请发起修改-时序图.png)；`createsAndReadsOwnPendingRequest` |
| updateRequest / withdrawRequest；C2-12 | 信息专家、高内聚 | BusinessRequestTest.`applicantWithdraws`；`updatesAndWithdrawsPendingRequest` |
| approveRequest / rejectRequest / deleteRejectedRequest；C2-13 | 信息专家、间接性、纯虚构 | [撤回驳回删除时序](../design/exports/33-oo-申请撤回驳回删除-时序图.png)；BusinessRequestTest.`applicantDeletesRejected`、`approvesLegacyRequest`；`serializesCompetingRequestTransitions` |
| 申请读写审计；C2-14 | 纯虚构、高内聚 | 图 38；`recordsRequestActionOutcomes` |
| 申请查看与实时撤权；C2-04/15 | 控制器、信息协作与统一授权 | 图 16/42；`requiresReadPermissionForRequests`、`inheritanceRevocationAppliesToExistingSession` |
| 申请模块结构；C2-16 | 低耦合、间接性、多态机制 | [领域与基础设施协作](../design/exports/21-oo-领域与基础设施协作图.png)；RequestArchitectureTest.`dependsOnPorts` |

纯领域测试见 [BusinessRequestTest](../../测试/后端/oo/java/simulation/domain/BusinessRequestTest.java)。测试证明对应场景、状态和边界，不直接测量“GRASP 得分”。

## 8. 老师追问时的回答依据

**“Spring 的 Controller 就是 GRASP Controller 吗？”**

不能只看注解。HTTP Controller 是边界适配；本项目真正组织系统用例的是 RoleInheritanceService、BusinessRequestService。回答时先说明谁接收系统操作、谁协调对象，再指出 HTTP 层与应用层的分工。

**“为什么不把审批资格判断也放进 BusinessRequest？”**

它知道申请状态、申请人和系统 ID，但没有当前员工的角色闭包、组织停用和重授信息。外部动作权限经 RequestAccess 实时检查；对象只维护自己掌握的状态与本人边界。

**“有 Repository 接口就满足低耦合了吗？”**

接口要表达业务需要并隔开实际变化。这里 RequestRepository 返回领域对象和业务分页切片，Service 不接收 JPA 实体；但事务注解、异常类型及其他模块依赖仍存在，改善有具体范围。

**“多态体现在哪里，是否有多个算法？”**

Service 字段采用端口接口，运行实例是 JPA/授权适配器，通过接口分派到实现。当前没有多套运行算法可选，因此只主张多态机制与替换边界，不声称复杂策略多态已经实现。

**“Creator 与 CloneFactory 有什么区别？”**

Creator 是创建职责应由谁承担；CloneFactory 是字段映射工具。快照由掌握关系数据的存储实现创建，新申请由授权后的应用用例发起创建；CloneFactory 处理结果/VO 映射，不决定创建授权或领域规则。

**“为什么还保留 Service，会不会贫血？”**

Service 有事务与跨对象协调责任。判断是否富行为，要看 RoleHierarchy 是否实际拥有关系计算、BusinessRequest 是否维护状态和申请人边界；不能以是否存在 Service 判断。

**“这些适配器是不是过度设计？”**

申请模块的端口隔开了已有授权/JPA 模型，并有架构回归约束，适合展示设计差异。其代价是更多类型和映射。项目没有给每个类机械增加接口，两个上下文端口共用一个适配器，也不为多态增加未被使用的实现。

**“如何现场证明？”**

先选一个 API，打开本文索引中的类图与时序，跟到真实方法；再运行对应领域或 HTTP 测试。前端演示先成功、再制造越权或状态冲突，核对拒绝结果与日志。部署后的日志与集群验证仍属于收尾第 5 项，不能用本地记录代替。

## 9. 验证与后续维护

本文为文档整理，没有修改生产代码、测试实现或 UML 源模型。本次核对本地文件链接、所列 API 的 operationId、类/方法与测试入口，并复查已有报告；没有重新运行后端或浏览器业务回归。

已有真实回归为 func 48 项、oo 52 项、浏览器 23 项通过，见 [执行报告](../../测试/归档/执行报告.md)；满血内存小样另有 10 项通过，不混入正式后端计数。历史报告只证明对应执行批次。

在 C2 根目录可运行以下定向验证；本段是复现命令，不是本轮新生成的测试结果：

```powershell
mvn -f backend-oo/pom.xml '-Dtest=RoleHierarchyTest,BusinessRequestTest,RequestArchitectureTest' test
python ./docs/design/build_staruml.py --check
```

完整真实 MySQL/Redis 回归使用 [测试说明](../../测试/归档/测试说明-整理前.md) 的运行器；HTTP 测试必须连接独立 `_test` 数据库。启动、操作入口和已完成功能见 [C2 README](入口说明-整理前.md)。

后续修改职责时同步本文、模块说明、图件和测试；不为满足九个名称强行增加类或抽象。[项目总结](项目总结-整理前.md)、`答辩文档.md`（本地待完善，暂不上传） 和 [部署交接说明](../../部署/归档/部署交接文档.md) 已形成。下一步明确集群参数，落地镜像、路由、配置、迁移与回退，并回填实际部署证据。剩余工作统一见 [开发规划与进度](开发方向细节.md)。
