# 课程 CloneFactory 注解处理器

源码取自厦门大学 Java EE 课程配套工程 `spring-fundamentals` 的 `clonefactory`，版本固定在提交 `8cb0524685d5539826bcc8b386a419cc11aad4e1`。五个 Java 源文件沿用原版实现，仅整理了 CopyFrom.java 的文件末尾空行，许可证见 [LICENSE](./LICENSE)。本目录的 Maven 配置负责以 Java 21 构建原版注解、`CopyProcessor` 及其服务注册文件。[上游工程](https://gitee.com/mingqcn/spring-fundamentals)

从 C1 的 `backend` 目录执行 `mvn -f tools/clonefactory/pom.xml install`，然后运行后端 Maven 命令。Dockerfile 在构建镜像时执行同一步骤。后端编译产物中的映射由处理器生成，生成源码位于 `target/generated-sources/annotations/cn/edu/xmu/clonefactory/util/CloneFactory.java`。
