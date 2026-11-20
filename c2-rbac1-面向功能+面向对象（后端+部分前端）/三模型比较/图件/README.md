# 三组模型图件索引

## 复用正式实现图

| 模型 | 类图 | 时序图 |
| --- | --- | --- |
| func偏贫血／过程式 | [PNG](../../docs/design/exports/02-func-新增角色继承-类图.png) / [SVG](../../docs/design/exports/02-func-新增角色继承-类图.svg) | [PNG](../../docs/design/exports/03-func-新增角色继承-时序图.png) / [SVG](../../docs/design/exports/03-func-新增角色继承-时序图.svg) |
| oo富行为 | [PNG](../../docs/design/exports/10-oo-新增角色继承-类图.png) / [SVG](../../docs/design/exports/10-oo-新增角色继承-类图.svg) | [PNG](../../docs/design/exports/11-oo-新增角色继承-时序图.png) / [SVG](../../docs/design/exports/11-oo-新增角色继承-时序图.svg) |
| 满血内存小样 | [PNG](./exports/01-full-class.png) / [SVG](./exports/01-full-class.svg) | [PNG](./exports/02-full-sequence.png) / [SVG](./exports/02-full-sequence.svg) |

前两组原生模型仍为 [C2-RBAC1.mdj](../../docs/design/C2-RBAC1.mdj)，共42张保持原样；新增两张的原生模型为 [三模型比较.mdj](./三模型比较.mdj)。不复制已有图件。

## 生成与校验

运行 `./export.ps1`，复用C2原生UML生成工厂的源码方法/字段、类框避让、引用和同步执行条检查，再调用StarUML官方CLI导出两张PNG与两张SVG。输出PNG经白底合成，试用水印完整保留。

本轮源模型含127个唯一图元、248个解析引用；生成器通过输入布局及执行条校验。导出后实际检查五个类框及连线，PNG两张目视复核：类名、方法和注记可读，主调用覆盖嵌套执行条，静态reachable使用MetaClass。顺序图只展现合法新增成功链，不穷举全部拒绝路径。

类图没有虚构Service：小样不含应用Service，按实际领域用例入口FullRoleHierarchy居中，区别于正式服务用例的Service居中规则。这一取舍已写入图内，避免为了形式新增空转发类。局部集合检查用文字注记，不画不存在的调用。

内存存储的lockWrites是边界约定，不能据此宣称真实事务或跨实例并发安全。图、源码与测试入口见 [比较设计](../三模型设计.md)、[测试说明](../../测试/模型比较/README.md)。
