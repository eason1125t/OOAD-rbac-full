# 课程 CloneFactory 注解处理器

源码取自厦门大学 Java EE 课程配套工程 `spring-fundamentals` 的 `clonefactory`，版本固定在提交 `8cb0524685d5539826bcc8b386a419cc11aad4e1`。五个 Java 源文件沿用原版实现，仅整理了 CopyFrom.java 的文件末尾空行，许可证见 [LICENSE](./LICENSE)。本目录的 Maven 配置负责以 Java 21 构建原版注解、`CopyProcessor` 及其服务注册文件。[上游工程](https://gitee.com/mingqcn/spring-fundamentals)

## 在 C2 中的构建和调用

从当前版本的后端根目录执行：

```powershell
mvn -f tools/clonefactory/pom.xml install
mvn test-compile
```

两版工具使用相同 Maven 坐标 `cn.edu.xmu:clonefactory:0.0.1-SNAPSHOT`，安装一次相同制品后可供两版编译。C2 的 [后端 Dockerfile](../../../部署/Dockerfile.backend) 已在镜像构建阶段执行同样的工具安装；完整操作见 [部署包](../../../部署/README.md)。

应用 `pom.xml` 把处理器列为 provided 依赖，并与 Lombok 一起显式放入 `annotationProcessorPaths`。处理器读取 `@CopyFrom/@CopyTo/@CopyNotNullFrom` 等注解，生成同名同类型字段的赋值代码。生成类位于 `target/generated-sources/annotations/cn/edu/xmu/clonefactory/util/CloneFactory.java`，运行时不是逐字段反射复制。

## 核心工具与特别技术

`CopyProcessor` 组织注解扫描及映射生成，JavaPoet 构造 Java 源码，AutoService 生成注解处理器服务注册。处理器配合 Lombok 的访问器生成，后端打包排除处理器工具制品本身。

组织/清单 VO、申请摘要/详情服务结果和公开 VO 使用这条映射链。权限敏感字段、系统与申请人身份、派生值及领域持久化转换仍按各自白名单和业务规则显式处理；不可变 record 当前采用明确构造，不为使用工具而破坏模型。

## 维护边界与验证

上游五个 Java 文件及许可证保留原版，不机械替换其注释或重构第三方实现。若修改映射注解，需要重新生成并检查生成源码，按项目要求执行 clean 构建和相应测试；删除构建目录须遵守项目禁止批量删除的约束，不手动批量删除目录。

本轮仅整理消费方说明与注释，未改变工具版本或映射注解。主工程编译检查处理器与 Lombok 能否正常协作，申请/组织相关测试核对公开字段行为。测试重做和 JaCoCo 报告另按 C2 收尾第 2 项归档。
