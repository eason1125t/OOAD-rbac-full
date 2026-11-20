# C2 部署验证

日期：2026-11-20（Asia/Shanghai）。本轮验证新增部署包，不改两版 Java 业务逻辑，不重新生成原 48/52 项后端和 23 项完整浏览器回归。

## 本次实际执行

| 项目 | 结果与边界 |
| --- | --- |
| 后端两个镜像 | 通过 Dockerfile 从源码构建，安装课程 CloneFactory；镜像不带测试源码 |
| 前端镜像 | Vue 类型检查、Vite 构建及 Nginx 运行通过 |
| 部署脚本单元验证 | 5 项通过；验证随机凭据、重复保留、非法已有凭据、错误配置与不执行变量展开 |
| 五服务本机容器 | frontend、backend-func、backend-oo、MySQL、Redis 均 healthy；使用独立数据卷和端口 18080 |
| HTTP 外部入口 | 7 组检查通过，见 HTTP冒烟结果.json |
| 真实页面 | 外部 Playwright 3 项通过：两版入口及双标签页登录、申请写入审批和退出隔离 |
| 数据库 | 两库均为 V1—V8；各版应用用户跨库 SELECT 被 MySQL 1142 拒绝 |
| Logback | 两版 CREATE/UPDATE/WITHDRAW 提交、终态拒绝日志与 HTTP 报告的申请 ID 对应 |
| 配置和 shell | Swarm 配置渲染、Compose 配置及四个 shell 脚本语法检查通过 |
| 备份 | 已实际导出两库 SQL 到私有忽略目录；未执行恢复测试 |

本机 Docker Swarm 状态保持 inactive，使用从同一 stack 生成的 Compose bridge 配置验证；没有启用本地集群，也没有部署课程服务器。Swarm secrets/service 初始化命令与多节点调度、私有仓库拉取、节点故障、TLS、真实课程原表和 Apifox 云端环境仍需队友实测。编译/基础镜像和终端有非阻断提示，原输出保留，不伪称无警告。

## 代码与运行

- [部署包](../../部署/README.md)：构建、凭据、stack、初始化和备份。
- [管理脚本测试](./test_manage.py)：`python 测试/部署/test_manage.py -v`。
- [HTTP 冒烟](./smoke.py)：`python 测试/部署/smoke.py --url http://127.0.0.1:18080 --exercise`。
- [外部浏览器配置](../前端/playwright.remote.config.ts)：设置 RBAC_DEPLOY_BASE_URL，运行 `npm --prefix 测试/前端 run test:remote`；本机使用 Edge。

HTTP `--exercise` 会新增、修改并撤回合成申请；浏览器隔离用例会新增并审批合成申请。应在约定演示环境运行，不指向正式业务数据。默认演示账号和权限见前端说明；完整继承/组织/Excel/并发验收仍按 [需求追踪](../需求追踪.md) 执行。

## 原始证据

- [命令](../结果/部署/命令.txt)、[镜像构建](../结果/部署/镜像构建输出.txt)、[本机镜像 ID](../结果/部署/本机镜像ID.json)。
- [func 初始化](../结果/部署/func初始化输出.txt)、[oo 初始化](../结果/部署/oo初始化输出.txt)、[容器状态](../结果/部署/容器状态.json)。
- [管理脚本输出](../结果/部署/管理脚本测试输出.txt)、[HTTP 结果](../结果/部署/HTTP冒烟结果.json)。
- [浏览器输出](../结果/部署/浏览器输出.txt)、[浏览器 JSON](../结果/部署/browser-results.json)。
- [数据库验证](../结果/部署/数据库验证.txt)、[对应 Logback](../结果/部署/Logback对应输出.txt)。

实际凭据、`.env.local`、渲染配置、SQL 备份和管理脚本合成夹具位于忽略目录，不进入 Git。上述本机结果不替代交接文档中的课程 Swarm 验收记录。
