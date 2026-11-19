---
name: uml-class-sequence-diagrams
description: 从代码或设计文档生成、重画、审查 UML 类图和顺序图时使用，尤其强调 Service 居中、类框与连线不重叠、execution specification 对齐、MetaClass lifeline、异常回复路径和静态方法调用。不要用于包图、ER 图、流程图或普通 UI 原型。
metadata:
  short-description: 生成和审查严格的 UML 类图、顺序图
---

# UML 类图与顺序图

## 开始前

1. 先读取当前实现代码和用户提供的参考图。图必须表达**现有代码**，不能按想象补类或方法。
2. 先找现有生成脚本和设计文档；优先修改现有脚本并重新生成 PNG + SVG，不要手工维护两套图。
3. 生成后必须打开 PNG 检查；自动检查通过不等于排版可读。

## ProjectMgt 项目约定

- 当前类图和顺序图生成脚本：`prototype/scripts/build-course-uml-reference-style.mjs`。
- 输出目录：`docs/design/`，同时生成 SVG 和 PNG。
- 设计文档：`docs/backend-design.md` 第 17 章。
- 修改图后必须重新运行脚本，并实际打开 PNG 检查。
## 类图规则

### 布局

- 每张类图以目标 Service 类为**几何中心和视觉中心**。
- 端口、Repository、领域对象、Entity、Adapter 分布在 Service 上下左右，不从页面一侧堆叠。
- 任意两个类框不得重叠，边框之间保留清晰留白。
- 依赖线不得穿过任何非端点类框。
- 长类名、长方法签名不得被裁切；必要时增加画布或缩短已展示的方法列表。

### UML 表达

- 依赖：虚线 + 空心箭头。
- 实现：虚线 + 空心三角；箭头必须从实现类指向接口，空心三角落在接口一端，例如 `JpaCourseRepositoryAdapter → CourseRepository`。
- 普通关联：实线 + 空心箭头，并在关联端清楚标注 role name。
- 对象字段形成 association，必须标注 role name。
- Association role name 写在箭头上方；垂直或斜向连线写在箭头左侧或左上方，不得压在线或类框上。
- 方法参数、局部变量和静态方法调用形成 dependency。
- 接口类标注 `«interface»`。
- **dependency line 上不写任何文字**。依赖关系由 Service 字段、方法参数或返回类型表达。
- 不为 Repository → Entity 强行画长关联线；优先在 Repository 方法签名中写出实体参数和返回类型。
- 类框应分为名称、属性、操作三个区。成员和方法只保留与当前模块有关的内容。

### 生成后自检

- 类框两两不重叠。
- 每条依赖/实现线不穿过非端点类框。
- Service 位于中心区域，周边留白均衡。
- 不存在回折、倒退、重复拐点或不必要的 U 形线。
- 如现有脚本支持，加入 `assertNoOverlap()` 和 `assertEdgesClearOfBoxes()`；生成失败时先修布局，不关闭检查。

## 顺序图规则

### Lifeline

- 最左侧调用者为 `obj: 调用者`。
- 普通对象 lifeline box 使用单行：`objectName: ClassName`。
- 静态方法所在 lifeline box 使用 stereotype：

```text
«MetaClass»
: ClassName
```

- 类名过长时自动减小字号，不能溢出 lifeline box。
- 不要用两个独立文本框分别写 object name 和 class name。

### 消息与执行条

- 同步调用：实线 + 实心箭头。
- 回复或异常：虚线 + 空心箭头。
- 同步消息按顺序编号；回复/异常不编号。
- 返回值用 `变量 = 方法(...)` 表达时，不再重复画普通返回线。
- 调用者保留覆盖整个同步调用的 execution specification。
- 被调用方每次独立同步调用都有**自己的短 execution specification**；不得把连续多次调用合并成一根长条。
- execution specification 顶边与进入消息同高。
- 消息箭头终点落在执行条**顶边靠近消息来向的边缘**：
  - 从左向右发送：落顶边左端点。
  - 从右向左发送：落顶边右端点。
- 箭头不要落在 lifeline 中心、执行条内部或执行条侧边中部。
- execution specification 底边不得压到 `opt/alt` 边框，至少保留明显间距。
- reply/exception 必须从发送方 execution specification 发出，并落到接收方 execution specification 边缘，不能从 lifeline 直接画。

### 异常路径

- 未被 `catch` 的异常不要逐段返回。
- 从实际抛出异常的 execution specification 直接返回 caller execution specification。
- 异常抛出前，来源执行条必须仍处于激活状态；不能出现执行条已结束、箭头从 lifeline 上冒出的情况。
- 未捕获异常通常不需要额外的 Service 转发箭头。

### 控制片段

- 条件分支使用 `opt` 或 `alt`。
- 片段内只放该分支实际发生的消息或异常。
- `loop` 框必须完整框住循环体对应的全部消息，以及这些消息在被调用方产生的所有 execution specification，包括嵌套自调用和静态方法调用的 execution specification。
- 循环前的查询、校验、编号计算等 execution specification 必须留在 `loop` 框外；循环后的批量保存、结果转换和返回等 execution specification 也必须在框外。
- 每轮循环中被调用方的 execution specification 必须各自完整位于 `loop` 框内，不得只把消息箭头框进去而把执行条留在框外。
- 表示整个公共方法调用的 caller 长 execution specification 可以跨越 `loop` 框；它不属于单轮循环体。除这类外层执行条外，循环体 execution specification 不得跨越 `loop` 的上边界或下边界。
- `loop` 框应绘制在消息和 execution specification 下层，使用透明或极浅填充，不能遮挡编号、文字、箭头和执行条。
- 片段边框不得穿过、切断或遮挡属于片段内容的 execution specification。
- 插入 `opt/alt/loop` 后重新检查所有消息、执行条和框线的几何关系。

## 推荐工作流

1. 从代码列出参与者、字段、方法、异常和调用顺序。
2. 先设计布局，再写生成脚本。
3. 生成 SVG，并渲染 PNG。
4. 自动检查：类框重叠、连线穿框、lifeline 文本溢出。
5. 目视检查：中心性、留白、箭头端点、执行条、`opt/alt`。
6. 更新设计文档中的图片链接和生成脚本说明。

## 参考

- 需要逐项验收时读取 `references/checklist.md`。
- 如项目已有 UML 脚本，优先在其基础上调整；本 skill 不规定固定目录名或渲染库。