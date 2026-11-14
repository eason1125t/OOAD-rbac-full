# C1 · RBAC0

## 运行数据库与初始化

使用 Java 21、Maven 3.9、Spring Boot 3.5.16 和 MySQL 8.4。IntelliJ IDEA 可直接打开 [backend/pom.xml](./backend/pom.xml)。在 Navicat 的 `ooad_rbac` 连接下确认 `ooad_rbac0` 库及本地项目账号可用。启动后 Flyway 写入表结构与迁移历史，Hibernate 校验映射。

从 C1 的 `backend` 目录运行。数据库用户和密码使用本地配置，勿将密码写进项目文件。

```powershell
$env:RBAC_DB_URL = 'jdbc:mysql://localhost:3306/ooad_rbac0'
$env:RBAC_DB_USER = '你的 MySQL 项目账号'
$env:RBAC_DB_PASSWORD = Read-Host 'MySQL 密码' -MaskInput
mvn package -DskipTests
java -jar target/rbac0-0.1.0-SNAPSHOT.jar
```

需要演示数据时执行如下命令。该命令初始化合成组织、员工、管理员范围、业务系统和授权关系，已经初始化的环境保留后续配置变动。测试账号凭据随登录模块启用。

```powershell
java -jar target/rbac0-0.1.0-SNAPSHOT.jar --init-demo
```

课程对照验收时，在受控环境显式导入三份工作簿。命令从 `backend` 目录运行，`../../excel` 指向项目根目录下的原表目录。各命令执行完成后进程退出。

```powershell
java -jar target/rbac0-0.1.0-SNAPSHOT.jar '--import-excel=../../excel'
```

同内容可重复导入。源字段与已有数据冲突时报告表和行号并回滚。部门目录保留原表上级名称，组织归属只认本公司下唯一匹配的部门。权限清单保留原文和空值，实际授权由角色与权限的配置产生。

## 验证

准备独立的 MySQL 测试库 `ooad_rbac0_test`，将 `RBAC_DB_URL` 指向该库，再运行 `mvn verify`。数据源指向其他数据库时只运行单元测试。测试库无需运行 `--init-demo`。首次演示初始化用例在测试事务中写入并回滚，可连续执行。JaCoCo HTML 生成在 `backend/target/site/jacoco/index.html`。

已验证 Flyway V1、V2 和 JPA 映射、三表导入及重复导入、待匹配员工归属、整批回滚、首次和重复演示初始化、命令行参数及 Excel 合并区域。隔离 MySQL 下连续运行两次 `mvn verify`，每次 7 项测试通过。第二次报告为行覆盖率 97.1%、分支覆盖率 87.8%。数据库结构与图见 [数据库设计](./docs/数据库设计.adoc)。

## 后续模块

登录、Redis 会话、实时授权、管理端和业务模拟端随各自模块交付。下一步先设计登录以及一条可对照的实时授权用例。
