# 技术栈、测试工具与约定

## 现状（开工前先确认）

| 部分 | 现状 |
|---|---|
| 需求 | `docs/requirements.md`（R 编号）+ `CONTEXT.md`（术语表）—— 唯一真相 |
| 接口 | `api/openapi.yaml`（OpenAPI 3.1，已同步到 Apifox 项目 8913225）—— 契约唯一真相 |
| 界面原型 | `prototype/`（Vue 3 + TS + Tailwind v4，一次性原型，**不改造**） |
| 数据库设计 | 第 4 阶段产出（尚未开始） |
| 生产代码 | **尚未开始**，按 TDD 从测试写起 |

**后端技术栈还没定**（Node/TS 还是 Java/Spring 由用户决定）。写第一行后端测试之前，先跟用户确认；下面的约定按 Node/TS 写，选别的栈就照同样思路平替。

## 建议的目录（产品代码，与原型分开）

```
server/          后端：领域规则、仓储、HTTP 层
  src/domain/    纯逻辑（最容易测：无 IO、无框架）
  src/repo/      仓储（真实数据库）
  src/http/      路由/控制器（薄，只做参数与错误码转换）
  test/          与 src 对应的测试
web/             产品前端（Vue3 + TS + Tailwind，从原型重写，不再改 prototype/）
  src/components/
  e2e/           Playwright 端到端（只覆盖关键路径）
api/openapi.yaml 契约
docs/            需求、数据库设计、api-map
```

## 工具

- **单元 / 组件测试**：Vitest（`npm test`）；Vue 组件用 `@vue/test-utils`。
- **端到端**：Playwright —— 已经装在 `prototype/node_modules`（用时设 `PLAYWRIGHT_BROWSERS_PATH=0`），`prototype/scripts/*.mjs` 里有现成写法可以参考（起 Vite dev server → 打开页面 → 断言）。
- **接口契约测试**：按 `api/openapi.yaml` 断言响应结构、分页字段与 4xx 的 `code`。Node 侧用 supertest 之类直接打 app，不要真的起外网服务。
- **数据库集成测试**：临时库 + 每个用例事务回滚；迁移脚本要在测试里跑一遍，保证「迁移 + 约束」和领域规则一致。

## 命名

- 文件：`R25b.my-grades.test.ts`（需求编号开头，便于按需求检索）
- 用例：`describe('R25b 学生的「我的成绩」页')` → `it('82 分权重 20% 显示 16.4')`
- 断言里的期望值写死（手算/手写常量），**不要**用被测代码算出来当期望。

## 命令（待项目落地后补齐）

```bash
# 后端
npm test                 # 全量单测（必须输出干净：无 warning）
npm test -- R25b         # 只跑某条需求相关的用例
# 前端
cd web && npm test
# 端到端
cd web && npx playwright test
```

## 与原型的关系

`prototype/` 用来「探索长什么样」；探索阶段的代码按 TDD 的例外处理，**不补测试、不改造**。产品前端 `web/` 从零开始、按测试写起，视觉与交互以原型截图（`prototype/review/*.png`）为参照。原型里那些正则/校验逻辑（例如「权重合计必须 100」「跨班归属判定」）在重写时**必须先写测试再实现**，不要直接复制过来。