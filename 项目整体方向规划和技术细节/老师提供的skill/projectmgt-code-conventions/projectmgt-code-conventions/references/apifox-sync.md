# OpenAPI 同步到 Apifox

## 固定文件

- 主契约：`api/openapi.yaml`
- 推送用 JSON：`api/openapi.json`
- 推送脚本：`prototype/scripts/push-apifox.mjs`
- 当前项目：`8913225`

## 步骤

1. 修改 `api/openapi.yaml`。
2. 用 js-yaml 从 YAML 生成 UTF-8 的 `api/openapi.json`。
3. 运行 `node scripts/check-openapi.mjs` 校验路径、模型和 `$ref`。
4. 在 `prototype` 目录运行：

```powershell
$env:APIFOX_ACCESS_TOKEN='<token>'
node scripts/push-apifox.mjs --project=8913225
```

脚本调用：

```text
POST https://api.apifox.com/v1/projects/{projectId}/import-openapi
```

覆盖选项：

- `endpointOverwriteBehavior=OVERWRITE_EXISTING`
- `schemaOverwriteBehavior=OVERWRITE_EXISTING`
- `deleteUnmatchedResources=true`

5. 成功输出必须满足：

```text
endpointFailed: 0
schemaFailed: 0
```

6. 调用 Project API MCP 的 `refresh_project_oas`，回读并确认新增路径、schema 和中文 tag。

## 禁止事项

- 不要用 PowerShell `Invoke-WebRequest -Body <string>` 推送。
- 不要在 spec 不完整时使用 `deleteUnmatchedResources=true`。
- 不要把 MCP 的只读工具误称为写工具。
- 不要在没有用户明确要求时执行覆盖推送。